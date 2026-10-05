# Trading Session Engine

证券交易状态管理服务：为订单入口、撮合与行情发布环节提供统一的交易时段与品种处置状态判定。
所有状态迁移由注入时钟驱动，全部变化写入审计日志，可重放重建任一时刻的完整状态，测试不依赖真实时间。

## 状态模型

### 交易时段（SessionPhase）

交易日由 `DaySchedule`（一组不重叠、升序的 `SessionWindow`，左闭右开）定义，窗口间隙视为闭市。

```
CLOSED → OPENING_AUCTION → CONTINUOUS_TRADING → BREAK → CONTINUOUS_TRADING → CLOSING_AUCTION → CLOSED
```

- `OPENING_AUCTION` 开盘集合竞价
- `CONTINUOUS_TRADING` 连续交易
- `CLOSING_AUCTION` 收盘集合竞价
- `BREAK` 盘中休市
- `CLOSED` 闭市

`SessionStateMachine.advance()` 按注入时钟推进；注入相同时钟序列，迁移逐次一致。

### 品种处置状态（InstrumentTradingState）

- `TRADING` 正常交易
- `HALTED` 停牌中（触发方式：`MANUAL` 人工 / `RULE_BASED` 规则）

复牌后品种状态从快照中移除（回到 `TRADING`），排队订单按复牌时刻时段重新归属，无状态残留。

### 市场熔断（CircuitBreakerPhase）

```
NONE → COOLING → RESUMING → NONE
```

- 指数相对参考值跌幅达到某级阈值即触发，进入 `COOLING`（冷却期，时长按级别配置）
- 冷却结束经时钟推进进入 `RESUMING`，确认恢复（`confirmCircuitBreakerResume`）后才回到 `NONE`
- 不得一步回到连续交易；更高级别触发会升级级别并重置冷却期

## 判定规则

订单/撤单请求在写锁内先推进时钟再判定，判定顺序如下（先到先得，结果确定）：

**订单（submitOrder）**
1. 熔断 `COOLING` → `REJECTED`（`circuit-breaker-cooling`）
2. 熔断 `RESUMING` → `REJECTED`（`circuit-breaker-resuming`）
3. 时段 `CLOSED`/`BREAK` → `REJECTED`（`session-closed` / `session-break`）
4. 集合竞价且配置禁止 → `REJECTED`（`auction-orders-disabled`）
5. 品种停牌：`REJECT` 策略 → `REJECTED`；`QUEUE` 策略 → `QUEUED`（复牌后归属复牌时段）
6. 价格越出价格带 → `REJECTED`（`price-out-of-band`）
7. 否则 `ACCEPTED`，归属判定时刻的时段

**撤单（submitCancel）**
1. 未知订单 → `REJECTED`（`unknown-order`）
2. 熔断 `COOLING` → `REJECTED`
3. 集合竞价且配置禁止 → `REJECTED`
4. 品种停牌且 `HaltedCancelPolicy=REJECT` → `REJECTED`
5. 否则 `ACCEPTED`

**价格限制**
- `STATIC`：价格带 = 静态参考价 × (1 ± percentLimit)，参考价固定
- `DYNAMIC`：参考价随最新成交价移动，价格带随行情滑动
- 价格带变化只影响变化之后到达的订单；已接受订单的判定结果不翻案（仍可正常撤单）

**停牌存量订单**
- `CANCEL_ALL`：停牌时全部撤销
- `KEEP`：保留至复牌

## 并发与一致性

- 所有状态迁移与请求判定持有写锁（`ReentrantReadWriteLock`），快照读取持有读锁
- 单次 `snapshot()` 读取得到自洽快照（`EngineSnapshot` 为不可变记录）
- 阶段切换瞬间到达的请求由写锁串行化，归属由判定时刻的状态唯一确定

## 持久化与崩溃恢复

- `FileAuditLog` 每条事件一行追加写并 `SYNC` 刷盘，已确认事件崩溃不丢失
- 未落审计的迁移不会半途生效：重启后状态停留在最后一条已确认事件
- `StateRestorer.restore()` 重放全部事件重建崩溃前状态；`replayTo(seq)` 重建任一时刻状态
- 重建结果与在线快照一致（由 `CrashRecoveryTest` 验证）

## 审计事件

| 事件 | 关键 payload |
| --- | --- |
| `SESSION_PHASE_CHANGED` | `phase`, `enteredAt` |
| `INSTRUMENT_HALTED` / `INSTRUMENT_RESUMED` | `instrumentId`, `trigger` |
| `PRICE_BAND_CHANGED` | `instrumentId`, `lower`, `upper` |
| `CIRCUIT_BREAKER_TRIGGERED` | `level`, `coolingEndsAt` |
| `CIRCUIT_BREAKER_PHASE_CHANGED` | `phase`, `level` |
| `ORDER_DECIDED` / `CANCEL_DECIDED` | `orderId`, `instrumentId`, `decision`, `phase`, `reason` |
| `RESTING_ORDERS_HANDLED` | `instrumentId`, `action`, `orderIds` |

## 配置项

**`EngineConfig`**（构造器注入）
| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `haltPolicies.restingOrderAction` | `CANCEL_ALL` | 停牌时存量订单处置：`CANCEL_ALL` / `KEEP` |
| `haltPolicies.haltedOrderPolicy` | `REJECT` | 停牌期间新订单：`REJECT` / `QUEUE` |
| `haltPolicies.haltedCancelPolicy` | `ACCEPT` | 停牌期间撤单：`ACCEPT` / `REJECT` |
| `orderAllowedInAuction` | `true` | 集合竞价是否接受订单 |
| `cancelAllowedInAuction` | `true` | 集合竞价是否接受撤单 |

**引擎构造参数**
| 参数 | 说明 |
| --- | --- |
| `clock` | 注入时钟（`SystemClock` / `ManualClock`） |
| `schedule` | 交易日时段表 `DaySchedule` |
| `circuitBreakerLevels` | 熔断级别列表：级别、跌幅阈值、冷却时长 |
| `auditLog` | 审计日志（`FileAuditLog` / `InMemoryAuditLog`） |

**`application.properties`**
| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `engine.audit-file` | `data/audit.log` | 审计日志文件路径 |

## REST 接口

- `GET /api/engine/snapshot`：当前自洽状态快照

## 构建与测试

```bash
mvn compile   # 编译
mvn test      # 全部测试（时段、停牌、价格限制、熔断、并发、崩溃恢复）
```

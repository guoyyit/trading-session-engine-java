# Trading Session Engine (Java)

证券交易状态管理服务：为订单入口、撮合与行情发布环节提供统一的交易时段（session）与品种处置状态判定。
状态迁移由**注入时钟**驱动，全部变化写入**只追加审计/事件日志**，可重放、可崩溃恢复；测试不依赖真实时间。

## 1. 设计目标与状态模型

### 1.1 市场级时段 `MarketPhase`

一个交易日由有序、不重叠的时段组成，顺序为：

| 阶段 | 含义 | 是否接受订单 |
| --- | --- | --- |
| `PRE_OPEN` | 盘前 | 否（`MARKET_CLOSED`） |
| `OPEN_AUCTION` | 开盘集合竞价 | 是 |
| `CONTINUOUS` | 连续交易 | 是 |
| `CLOSE_AUCTION` | 收盘集合竞价 | 是 |
| `CLOSED` | 休市（终态） | 否（`MARKET_CLOSED`），收盘时清理全部存量/排队订单 |
| `BREAKER_HALT` | 市场熔断-停牌阶段 | 否（`MARKET_BREAKER_HALT`） |
| `BREAKER_COOLDOWN` | 市场熔断-冷却阶段 | 否（`MARKET_BREAKER_COOLDOWN`） |

- 时段按半开区间 `[start, end)` 归属：**边界时刻归属后一时段**，因此阶段切换瞬间任一请求的时段归属唯一确定。
- 时区由 `SessionSchedule.zone` 显式固定，注入的 `Instant` 与时段墙钟时间的映射在任何环境下一致。
- 注入相同的时钟序列，阶段迁移逐次一致（见 `PhaseTransitionTest`）。

### 1.2 品种级状态 `InstrumentStatus`

- `ACTIVE`：正常交易。
- `HALTED`：停牌，停牌原因 `HaltReason`：`MANUAL`（人工）、`RULE_STATIC_PRICE` /
  `RULE_DYNAMIC_PRICE`（越价规则）、`RULE_INSTRUMENT_BREAKER`（预留的品种熔断）。

每个品种还持有：静态价格带、动态价格带（均带版本号）、存量订单（含其被接受时的时段与价格带版本）、
停牌期间排队订单、停复牌时间戳。

### 1.3 价格带 `PriceBand`

- **静态带**：以当日参考价为锚，`[ref*(1-Ps), ref*(1+Ps)]`，默认 `Ps=10%`。
- **动态带**：以最新成交价为锚，`[last*(1-Pd), last*(1+Pd)]`，默认 `Pd=3%`，随行情移动。
- 每个价格带都是**不可变对象并带 `version`**；行情移动动态带只会产生新版本。
- 订单判定引用其决策时刻的那一版价格带；价格带移动**不会回溯改变**同一订单的既有判定，
  已接受订单仍记录其接受时的带版本，判定无歧义。
- 可配置“越静态/动态限即规则停牌”（`registerInstrument(..., haltOnStaticViolation, haltOnDynamicViolation)`）。

### 1.4 市场熔断 `BreakerLevel` / `CircuitBreaker`

分级：`LEVEL1 < LEVEL2 < LEVEL3 < CLOSE`。每级配置停牌时长与冷却时长。恢复**必须分两阶段**：

```
触发 -> BREAKER_HALT(拒绝全部订单/撤单)
      -> 到达 haltUntil -> BREAKER_COOLDOWN(继续拒绝订单)
      -> 到达 cooldownUntil -> 回到连续交易/集合竞价
```

- 冷却时长从**停牌结束时刻**起算，绝不会一步回到连续交易。
- `CLOSE` 为终态级别：触发后直接进入 `CLOSED`，清理订单，当日不再交易。
- 冷却结束时按当日时间表决定回到 `OPEN_AUCTION` / `CONTINUOUS` / `CLOSE_AUCTION`，
  若已过收盘则进入 `CLOSED`。

### 1.5 停牌处置策略 `HaltPolicy`（全部可配置）

`HaltPolicy(restingOrderStrategy, incomingOrderStrategy, incomingCancelStrategy)`，取值 `ACCEPT / REJECT / QUEUE`：

- `restingOrderStrategy`：停牌瞬间对存量订单——`REJECT`（撤销）或 `ACCEPT`（保留）。
- `incomingOrderStrategy`：停牌期间到达的订单——`REJECT`（拒绝，原因 `INSTRUMENT_HALTED`）
  或 `QUEUE`（排队，复牌时按当时阶段决定放行/拒绝）。
- `incomingCancelStrategy`：停牌期间到达的撤单——`ACCEPT`（允许撤销存量）或 `REJECT`。
- 复牌会清空排队残留；被放行订单以**复牌后的阶段**重新归属，不会错归到停牌前时段。

## 2. 判定规则（确定性优先级）

`onOrder` 在同一把锁内按固定优先级判定，返回 `Decision(action, reason, phaseAtDecision, bandVersions)`：

1. 品种不存在 → `UNKNOWN_INSTRUMENT`
2. 熔断停牌阶段 → `MARKET_BREAKER_HALT`；冷却阶段 → `MARKET_BREAKER_COOLDOWN`
3. `PRE_OPEN/CLOSED` → `MARKET_CLOSED`
4. 订单号重复（存量或排队中）→ `DUPLICATE_ORDER_ID`
5. 品种停牌 → 按停牌到达策略（排队/拒绝）
6. 静态带越限 → `PRICE_OUTSIDE_STATIC_BAND`（可选触发规则停牌）
7. 动态带越限 → `PRICE_OUTSIDE_DYNAMIC_BAND`（可选触发规则停牌）
8. 处于集合竞价/连续交易 → `ACCEPT`；其余 → `PHASE_NOT_ORDER_ACCEPTING`

撤单 `onCancel` 同理，并区分停牌撤单策略、熔断阶段、`ORDER_NOT_FOUND` 等。

## 3. 并发一致性

`TradingSessionEngine` 的所有公开方法由**同一把内置锁**串行化（线性化）：

- `snapshot()` 返回的 `MarketSnapshot` 是某一事件序列号下不可变、自洽的快照；
  读不会看到迁移中间态。
- 请求在阶段切换瞬间只可能在锁内观察到唯一阶段，归属唯一。

## 4. 持久化、审计与崩溃恢复

- 事件类型见 `audit/EventType`：`SESSION_STARTED / PHASE_CHANGED / INSTRUMENT_REGISTERED /
  INSTRUMENT_HALTED / INSTRUMENT_RESUMED / ORDER_ACCEPTED / ORDER_REJECTED / ORDER_QUEUED /
  ORDER_CANCELLED / CANCEL_REJECTED / STATIC_BAND_SET / DYNAMIC_BAND_MOVED /
  BREAKER_TRIGGERED / BREAKER_HALT_ENDED / BREAKER_COOLDOWN_ENDED`，以及每个请求都写的 `COMMAND`。
- `AuditEvent(sequence, type, clock, payload)`：全局单调序号、注入时钟时间戳、含全部重放所需字段的 JSON。
- 提交采用 **durable-first**：一次操作产生的事件作为一个**原子批次**追加（单次写入 + `fsync`），
  成功后才折叠进内存状态。崩溃时整个操作要么全部生效、要么全部不生效，不存在半途生效。
- `FileEventStore` 为 JSON Lines：恢复时尾部截断的残行被忽略，之前完整事件全部有效。
- `Replayer` / `StateEventApplier` 用与在线引擎**同一套**折叠逻辑重建状态，
  因此“重建结果 == 同一序列号的在线状态”。事件载荷中的时间戳用于重建，不读墙钟。

## 5. 配置项（`application.properties`，均有默认值）

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `trading.price-band.static-percentage` | `0.10` | 静态带半宽（占参考价比例） |
| `trading.price-band.dynamic-percentage` | `0.03` | 动态带半宽（占最新价比例） |
| `trading.halt.resting-strategy` | `REJECT` | 停牌时存量订单策略：`ACCEPT/REJECT/QUEUE` |
| `trading.halt.incoming-order-strategy` | `REJECT` | 停牌期间到达订单策略 |
| `trading.halt.incoming-cancel-strategy` | `ACCEPT` | 停牌期间撤单策略 |
| `trading.event-log` | `./data/trading-events.log` | 持久化审计/事件日志路径 |

熔断各级时长在 `EngineAutoConfiguration.defaultBreakerConfig()`：L1 5min+3min、
L2 10min+5min、L3 15min+10min、CLOSE 终态。默认时间表（Asia/Shanghai）：
开盘集合竞价 09:15–09:25、连续 09:30–14:57、收盘集合竞价 14:57–15:00。
编程接入时可用 `EngineConfig` / `BreakerConfig` / `SessionSchedule` 完全自定义。

## 6. REST 接口

| 方法与路径 | 作用 |
| --- | --- |
| `GET /api/trading/snapshot` | 查询自洽快照 |
| `POST /api/trading/clock/tick` | 推进注入时钟驱动的时间迁移 |
| `POST /api/trading/orders` | 提交订单，返回 `Decision` |
| `POST /api/trading/cancels` | 提交撤单 |
| `POST /api/trading/quotes` | 推送行情（移动动态带） |
| `POST /api/trading/instruments/{id}/halt/{reason}` | 人工/规则停牌 |
| `POST /api/trading/instruments/{id}/resume` | 复牌 |
| `POST /api/trading/breakers/{LEVEL}` | 触发分级熔断 |

## 7. 运行与测试

```bash
mvn test          # 全部测试，使用 MutableClock，不依赖真实时间
mvn spring-boot:run
```

自动化测试覆盖：阶段完整迁移与相同时钟序列一致性、边界归属、停牌存量/到达/撤单策略与复牌无残留、
静态/动态价格带及版本化判定、分级熔断 halt→cooldown→连续恢复与 CLOSE 终态、
文件日志重放与在线一致、尾部残批容错、并发读写快照自洽。

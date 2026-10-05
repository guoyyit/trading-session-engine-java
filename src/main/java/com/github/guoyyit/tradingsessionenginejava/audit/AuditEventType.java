package com.github.guoyyit.tradingsessionenginejava.audit;

/**
 * 审计事件类型：覆盖全部状态迁移与操作。
 */
public enum AuditEventType {

    /** 时段迁移 */
    SESSION_PHASE_CHANGED,

    /** 品种停牌 */
    INSTRUMENT_HALTED,

    /** 品种复牌 */
    INSTRUMENT_RESUMED,

    /** 价格带变化 */
    PRICE_BAND_CHANGED,

    /** 熔断触发 */
    CIRCUIT_BREAKER_TRIGGERED,

    /** 熔断阶段迁移（冷却结束/恢复确认） */
    CIRCUIT_BREAKER_PHASE_CHANGED,

    /** 订单请求判定 */
    ORDER_DECIDED,

    /** 撤单请求判定 */
    CANCEL_DECIDED,

    /** 存量订单处置（停牌时） */
    RESTING_ORDERS_HANDLED,

    /** 引擎快照（持久化检查点） */
    SNAPSHOT
}

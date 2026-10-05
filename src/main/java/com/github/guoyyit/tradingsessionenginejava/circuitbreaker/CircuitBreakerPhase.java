package com.github.guoyyit.tradingsessionenginejava.circuitbreaker;

/**
 * 市场熔断阶段。
 */
public enum CircuitBreakerPhase {

    /** 无熔断 */
    NONE,

    /** 熔断触发，交易暂停（冷却期） */
    COOLING,

    /** 冷却结束后的恢复确认阶段，之后才能回到连续交易 */
    RESUMING
}

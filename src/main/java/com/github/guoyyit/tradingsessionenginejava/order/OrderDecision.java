package com.github.guoyyit.tradingsessionenginejava.order;

/**
 * 引擎对订单/撤单请求的确定性判定结果。
 */
public enum OrderDecision {

    /** 接受，归属当前时段 */
    ACCEPTED,

    /** 排队（如停牌 QUEUE 策略），复牌后归属复牌时段 */
    QUEUED,

    /** 拒绝（时段不允许、停牌 REJECT、越限、熔断冷却中等） */
    REJECTED
}

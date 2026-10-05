package com.github.guoyyit.tradingsessionenginejava.instrument;

/**
 * 停牌期间新到达订单的处理策略。
 */
public enum HaltedOrderPolicy {

    /** 直接拒绝 */
    REJECT,

    /** 排队等待复牌后归属复牌时段 */
    QUEUE
}

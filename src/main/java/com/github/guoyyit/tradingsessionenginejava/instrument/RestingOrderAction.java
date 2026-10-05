package com.github.guoyyit.tradingsessionenginejava.instrument;

/**
 * 停牌时存量订单的处置策略。
 */
public enum RestingOrderAction {

    /** 全部撤销 */
    CANCEL_ALL,

    /** 保留至复牌 */
    KEEP
}

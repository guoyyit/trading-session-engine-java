package com.github.guoyyit.tradingsessionenginejava.session;

/**
 * 交易时段阶段。
 */
public enum SessionPhase {

    /** 开盘集合竞价 */
    OPENING_AUCTION,

    /** 连续交易 */
    CONTINUOUS_TRADING,

    /** 收盘集合竞价 */
    CLOSING_AUCTION,

    /** 盘中休市（如午休） */
    BREAK,

    /** 闭市（当日交易结束或尚未开市） */
    CLOSED
}

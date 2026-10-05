package com.github.guoyyit.tradingsessionenginejava.instrument;

/**
 * 停牌期间撤单请求的处理策略。
 */
public enum HaltedCancelPolicy {

    /** 允许撤单 */
    ACCEPT,

    /** 拒绝撤单 */
    REJECT
}

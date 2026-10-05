package com.github.guoyyit.tradingsessionenginejava.instrument;

/**
 * 停牌触发方式。
 */
public enum HaltTrigger {

    /** 人工触发 */
    MANUAL,

    /** 规则触发（如涨跌幅、异常波动） */
    RULE_BASED
}

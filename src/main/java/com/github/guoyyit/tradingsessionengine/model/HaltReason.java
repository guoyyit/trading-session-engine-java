package com.github.guoyyit.tradingsessionengine.model;

/** Why an instrument was halted. */
public enum HaltReason {
    MANUAL,
    RULE_STATIC_PRICE,
    RULE_DYNAMIC_PRICE,
    RULE_INSTRUMENT_BREAKER
}

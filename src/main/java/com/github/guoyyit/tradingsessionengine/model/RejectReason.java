package com.github.guoyyit.tradingsessionengine.model;

/** Exhaustive list of deterministic reject / cancel-refusal reasons. */
public enum RejectReason {
    NONE,
    MARKET_CLOSED,
    PHASE_NOT_ORDER_ACCEPTING,
    INSTRUMENT_HALTED,
    MARKET_BREAKER_HALT,
    MARKET_BREAKER_COOLDOWN,
    PRICE_OUTSIDE_STATIC_BAND,
    PRICE_OUTSIDE_DYNAMIC_BAND,
    UNKNOWN_INSTRUMENT,
    ORDER_NOT_FOUND,
    ORDER_BELONGS_TO_OTHER_PHASE,
    DUPLICATE_ORDER_ID
}

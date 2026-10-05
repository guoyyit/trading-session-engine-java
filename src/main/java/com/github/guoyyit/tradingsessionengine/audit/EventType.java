package com.github.guoyyit.tradingsessionengine.audit;

/**
 * The complete set of audited occurrences. State-changing events are the only
 * events replayed during recovery; {@code COMMAND} events are pure audit
 * records of every inbound request and its verdict.
 */
public enum EventType {
    COMMAND,
    SESSION_STARTED,
    PHASE_CHANGED,
    INSTRUMENT_REGISTERED,
    INSTRUMENT_HALTED,
    INSTRUMENT_RESUMED,
    ORDER_ACCEPTED,
    ORDER_REJECTED,
    ORDER_QUEUED,
    ORDER_CANCELLED,
    CANCEL_REJECTED,
    STATIC_BAND_SET,
    DYNAMIC_BAND_MOVED,
    BREAKER_TRIGGERED,
    BREAKER_HALT_ENDED,
    BREAKER_COOLDOWN_ENDED
}

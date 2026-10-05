package com.github.guoyyit.tradingsessionengine.model;

/**
 * Circuit breaker severity levels. Higher levels impose longer halts and
 * stricter request handling; {@link #CLOSE} terminates trading for the day.
 */
public enum BreakerLevel {
    LEVEL1,
    LEVEL2,
    LEVEL3,
    CLOSE
}

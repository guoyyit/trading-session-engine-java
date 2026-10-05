package com.github.guoyyit.tradingsessionengine.model;

/**
 * Market-wide phases of a trading day.
 *
 * <p>Normal sequence: PRE_OPEN -&gt; OPEN_AUCTION -&gt; CONTINUOUS -&gt;
 * CLOSE_AUCTION -&gt; CLOSED. When a market-wide circuit breaker is active the
 * sequence detours through BREAKER_HALT -&gt; BREAKER_COOLDOWN.
 */
public enum MarketPhase {
    PRE_OPEN,
    OPEN_AUCTION,
    CONTINUOUS,
    CLOSE_AUCTION,
    CLOSED,
    BREAKER_HALT,
    BREAKER_COOLDOWN
}

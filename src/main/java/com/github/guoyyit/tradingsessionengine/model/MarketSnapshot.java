package com.github.guoyyit.tradingsessionengine.model;

import java.time.Instant;
import java.util.List;

/**
 * Immutable, self-consistent snapshot of the whole market state at one logical
 * instant (one applied event sequence number).
 */
public record MarketSnapshot(String tradingDay,
                             MarketPhase phase,
                             Instant phaseSince,
                             BreakerSnapshot breaker,
                             List<InstrumentSnapshot> instruments,
                             long sequence) {
}

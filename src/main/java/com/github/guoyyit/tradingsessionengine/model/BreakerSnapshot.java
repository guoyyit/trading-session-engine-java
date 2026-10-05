package com.github.guoyyit.tradingsessionengine.model;

import java.time.Instant;

/** Immutable view of the circuit-breaker part of the state. */
public record BreakerSnapshot(boolean active,
                              BreakerLevel level,
                              MarketPhase haltPhase,
                              Instant triggeredAt,
                              Instant haltUntil,
                              Instant cooldownUntil,
                              long haltsToday) {
}

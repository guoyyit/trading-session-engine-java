package com.github.guoyyit.tradingsessionengine.config;

import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;

import java.time.Duration;

/**
 * Parameters of one circuit-breaker level: the halt (order-rejecting) stage,
 * then the mandatory cooldown (order-queueing) stage.
 */
public record BreakerLevelSpec(BreakerLevel level,
                               Duration haltDuration,
                               Duration cooldownDuration) {
}

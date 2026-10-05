package com.github.guoyyit.tradingsessionengine.config;

import java.util.List;

/** All breaker levels in ascending severity order. */
public record BreakerConfig(List<BreakerLevelSpec> levels) {
}

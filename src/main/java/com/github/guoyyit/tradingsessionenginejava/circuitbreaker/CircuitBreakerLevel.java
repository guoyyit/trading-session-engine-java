package com.github.guoyyit.tradingsessionenginejava.circuitbreaker;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * 熔断级别定义：市场指数相对参考值的跌幅阈值与对应冷却时长。
 */
public record CircuitBreakerLevel(int level, BigDecimal declinePercent, Duration coolingDuration) {

    public CircuitBreakerLevel {
        if (level <= 0) {
            throw new IllegalArgumentException("level must be positive");
        }
        Objects.requireNonNull(declinePercent, "declinePercent");
        Objects.requireNonNull(coolingDuration, "coolingDuration");
    }
}

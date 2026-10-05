package com.github.guoyyit.tradingsessionenginejava.circuitbreaker;

import com.github.guoyyit.tradingsessionenginejava.clock.TradingClock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 市场熔断管理：分级触发、冷却恢复。
 * 恢复必须经过 COOLING -> RESUMING -> NONE，不得一步回到连续交易。
 */
public final class CircuitBreakerManager {

    private final TradingClock clock;
    private final List<CircuitBreakerLevel> levels;
    private BigDecimal marketReference;
    private CircuitBreakerPhase phase = CircuitBreakerPhase.NONE;
    private int currentLevel;
    private Instant coolingEndsAt;

    public CircuitBreakerManager(TradingClock clock, List<CircuitBreakerLevel> levels) {
        this.clock = clock;
        this.levels = List.copyOf(levels);
    }

    /**
     * 市场指数行情更新，可能触发熔断。
     */
    public void onMarketIndex(BigDecimal indexValue, Instant at) {
        if (marketReference == null || indexValue == null) {
            return;
        }
        BigDecimal decline = marketReference.subtract(indexValue)
                .divide(marketReference, 8, java.math.RoundingMode.HALF_UP);
        CircuitBreakerLevel triggered = null;
        for (CircuitBreakerLevel level : levels) {
            if (decline.compareTo(level.declinePercent()) >= 0
                    && (triggered == null || level.level() > triggered.level())) {
                triggered = level;
            }
        }
        if (triggered == null || triggered.level() <= currentLevel) {
            return;
        }
        currentLevel = triggered.level();
        phase = CircuitBreakerPhase.COOLING;
        coolingEndsAt = at.plus(triggered.coolingDuration());
    }

    /**
     * 设置市场参考值（如前收盘指数）。
     */
    public void setMarketReference(BigDecimal reference) {
        this.marketReference = reference;
    }

    /**
     * 时钟推进：冷却结束进入 RESUMING，确认后回到 NONE。
     */
    public boolean advance() {
        if (phase == CircuitBreakerPhase.COOLING
                && coolingEndsAt != null
                && !clock.now().isBefore(coolingEndsAt)) {
            phase = CircuitBreakerPhase.RESUMING;
            return true;
        }
        return false;
    }

    public CircuitBreakerPhase currentPhase() {
        return phase;
    }

    public int currentLevel() {
        return currentLevel;
    }

    public Optional<Instant> coolingEndsAt() {
        return Optional.ofNullable(coolingEndsAt);
    }

    /**
     * 恢复确认：仅在 RESUMING 阶段有效。
     */
    public void confirmResume() {
        if (phase != CircuitBreakerPhase.RESUMING) {
            return;
        }
        phase = CircuitBreakerPhase.NONE;
        currentLevel = 0;
        coolingEndsAt = null;
    }

    public List<CircuitBreakerLevel> levels() {
        return levels;
    }
}

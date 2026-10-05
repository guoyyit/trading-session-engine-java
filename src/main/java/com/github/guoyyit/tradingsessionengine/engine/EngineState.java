package com.github.guoyyit.tradingsessionengine.engine;

import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The complete mutable engine state. Exactly one instance exists; all reads
 * derive an immutable snapshot from it under a single lock so state queries
 * never observe a torn transition.
 */
public final class EngineState {
    public volatile String tradingDay;
    public volatile MarketPhase phase = MarketPhase.PRE_OPEN;
    public volatile Instant phaseSince;
    public volatile MarketPhase preBreakerPhase;
    public volatile boolean breakerActive;
    public volatile BreakerLevel breakerLevel;
    public volatile Instant breakerTriggeredAt;
    public volatile Instant breakerHaltUntil;
    public volatile Instant breakerCooldownStartedAt;
    public volatile Instant breakerCooldownUntil;
    public volatile long breakerHaltsToday;
    public volatile boolean started;
    public final Map<String, InstrumentState> instruments = new LinkedHashMap<>();

    public EngineState() {
    }
}

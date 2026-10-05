package com.github.guoyyit.tradingsessionengine.service;

import com.github.guoyyit.tradingsessionengine.clock.Clock;
import com.github.guoyyit.tradingsessionengine.engine.TradingSessionEngine;
import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.CancelRequest;
import com.github.guoyyit.tradingsessionengine.model.Decision;
import com.github.guoyyit.tradingsessionengine.model.HaltReason;
import com.github.guoyyit.tradingsessionengine.model.MarketSnapshot;
import com.github.guoyyit.tradingsessionengine.model.OrderRequest;
import com.github.guoyyit.tradingsessionengine.model.QuoteTick;

import java.math.BigDecimal;

/**
 * Spring-facing facade over {@link TradingSessionEngine}; the engine itself is
 * framework-free. A clock-driven call ({@link #advanceClock()}) lets callers
 * push deterministic time without the engine reading the wall clock.
 */
public class TradingService {

    private final TradingSessionEngine engine;
    private final Clock clock;

    public TradingService(TradingSessionEngine engine, Clock clock) {
        this.engine = engine;
        this.clock = clock;
    }

    public void start() {
        engine.start();
    }

    public void registerInstrument(String instrumentId,
                                   BigDecimal staticReferencePrice,
                                   boolean haltOnStaticViolation,
                                   boolean haltOnDynamicViolation) {
        engine.registerInstrument(instrumentId, staticReferencePrice,
                haltOnStaticViolation, haltOnDynamicViolation);
    }

    public void advanceClock() {
        engine.tick();
    }

    public void halt(String instrumentId, HaltReason reason) {
        engine.halt(instrumentId, reason);
    }

    public void resume(String instrumentId) {
        engine.resume(instrumentId);
    }

    public Decision submitOrder(OrderRequest request) {
        return engine.onOrder(request);
    }

    public Decision cancel(CancelRequest request) {
        return engine.onCancel(request);
    }

    public void quote(QuoteTick tick) {
        engine.onQuote(tick);
    }

    public void triggerBreaker(BreakerLevel level) {
        engine.triggerBreaker(level);
    }

    public MarketSnapshot snapshot() {
        return engine.snapshot();
    }

    public Clock clock() {
        return clock;
    }
}

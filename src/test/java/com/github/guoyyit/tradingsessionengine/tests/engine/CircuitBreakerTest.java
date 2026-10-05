package com.github.guoyyit.tradingsessionengine.tests.engine;

import com.github.guoyyit.tradingsessionengine.audit.InMemoryEventStore;
import com.github.guoyyit.tradingsessionengine.clock.MutableClock;
import com.github.guoyyit.tradingsessionengine.engine.TradingSessionEngine;
import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.Decision;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;
import com.github.guoyyit.tradingsessionengine.model.OrderRequest;
import com.github.guoyyit.tradingsessionengine.model.RejectReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CircuitBreakerTest {

    private OrderRequest order(String id) {
        return new OrderRequest(id, EngineFixtures.INSTRUMENT, new BigDecimal("10.00"), 100);
    }

    @Test
    void gradedBreakerPassesThroughHaltThenCooldownBeforeContinuous() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());

        engine.triggerBreaker(BreakerLevel.LEVEL1);
        assertEquals(MarketPhase.BREAKER_HALT, engine.snapshot().phase());

        // orders rejected during the halt stage
        Decision halted = engine.onOrder(order("h1"));
        assertEquals(OrderAction.REJECT, halted.action());
        assertEquals(RejectReason.MARKET_BREAKER_HALT, halted.reason());

        // after 5 minutes the halt ends, but recovery must enter COOLDOWN,
        // never jump straight back to continuous trading
        clock.setTime(java.time.Instant.parse("2026-10-05T10:05:00Z"));
        engine.tick();
        assertEquals(MarketPhase.BREAKER_COOLDOWN, engine.snapshot().phase());
        Decision cooling = engine.onOrder(order("c1"));
        assertEquals(OrderAction.REJECT, cooling.action());
        assertEquals(RejectReason.MARKET_BREAKER_COOLDOWN, cooling.reason());

        // cooldown lasts another 3 minutes before continuous trading resumes
        clock.setTime(java.time.Instant.parse("2026-10-05T10:08:00Z"));
        engine.tick();
        assertEquals(MarketPhase.CONTINUOUS, engine.snapshot().phase());
        assertEquals(OrderAction.ACCEPT, engine.onOrder(order("ok1")).action());
    }

    @Test
    void breakerDoesNotRecoverEarly() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());
        engine.triggerBreaker(BreakerLevel.LEVEL1);

        clock.setTime(java.time.Instant.parse("2026-10-05T10:07:59Z"));
        engine.tick();
        assertEquals(MarketPhase.BREAKER_COOLDOWN, engine.snapshot().phase());
    }

    @Test
    void higherLevelHasLongerStages() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());

        engine.triggerBreaker(BreakerLevel.LEVEL2);
        clock.setTime(java.time.Instant.parse("2026-10-05T10:10:00Z"));
        engine.tick();
        assertEquals(MarketPhase.BREAKER_COOLDOWN, engine.snapshot().phase());

        clock.setTime(java.time.Instant.parse("2026-10-05T10:14:59Z"));
        engine.tick();
        assertEquals(MarketPhase.BREAKER_COOLDOWN, engine.snapshot().phase());

        clock.setTime(java.time.Instant.parse("2026-10-05T10:15:00Z"));
        engine.tick();
        assertEquals(MarketPhase.CONTINUOUS, engine.snapshot().phase());
    }

    @Test
    void closeLevelEndsTradingAndRejectsAllOrders() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());
        engine.onOrder(order("resting"));

        engine.triggerBreaker(BreakerLevel.CLOSE);

        assertEquals(MarketPhase.CLOSED, engine.snapshot().phase());
        assertEquals(0, engine.snapshot().instruments().get(0).restingOrderCount());
        assertEquals(OrderAction.REJECT, engine.onOrder(order("late")).action());
        assertEquals(RejectReason.MARKET_CLOSED, engine.onOrder(order("late2")).reason());
    }
}

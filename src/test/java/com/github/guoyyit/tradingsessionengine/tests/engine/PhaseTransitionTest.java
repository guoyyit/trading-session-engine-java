package com.github.guoyyit.tradingsessionengine.tests.engine;

import com.github.guoyyit.tradingsessionengine.audit.InMemoryEventStore;
import com.github.guoyyit.tradingsessionengine.clock.MutableClock;
import com.github.guoyyit.tradingsessionengine.engine.TradingSessionEngine;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;
import com.github.guoyyit.tradingsessionengine.model.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhaseTransitionTest {

    private List<MarketPhase> drive(TradingSessionEngine engine, MutableClock clock, String[] instants) {
        List<MarketPhase> phases = new ArrayList<>();
        for (String iso : instants) {
            clock.setTime(Instant.parse(iso));
            engine.tick();
            phases.add(engine.snapshot().phase());
        }
        return phases;
    }

    @Test
    void walksTheFullTradingDayInOrder() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T09:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = new TradingSessionEngine(
                clock, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), store);
        engine.start();

        List<MarketPhase> phases = drive(engine, clock, new String[]{
                "2026-10-05T09:14:59Z",
                "2026-10-05T09:15:00Z",
                "2026-10-05T09:24:59Z",
                "2026-10-05T09:30:00Z",
                "2026-10-05T14:57:00Z",
                "2026-10-05T15:00:00Z",
                "2026-10-05T15:30:00Z"
        });

        assertEquals(List.of(
                MarketPhase.PRE_OPEN,
                MarketPhase.OPEN_AUCTION,
                MarketPhase.OPEN_AUCTION,
                MarketPhase.CONTINUOUS,
                MarketPhase.CLOSE_AUCTION,
                MarketPhase.CLOSED,
                MarketPhase.CLOSED), phases);
    }

    @Test
    void boundaryInstantBelongsToTheLaterPhase() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T09:30:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = new TradingSessionEngine(
                clock, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), store);
        engine.start();
        engine.tick();
        assertEquals(MarketPhase.CONTINUOUS, engine.snapshot().phase());
    }

    @Test
    void reproducesIdenticalTransitionsForTheSameClockSequence() {
        String[] sequence = {
                "2026-10-05T09:15:00Z",
                "2026-10-05T09:30:00Z",
                "2026-10-05T14:57:00Z",
                "2026-10-05T15:00:00Z"
        };

        MutableClock clockA = EngineFixtures.clockAt("2026-10-05T09:00:00Z");
        InMemoryEventStore storeA = new InMemoryEventStore();
        TradingSessionEngine engineA = new TradingSessionEngine(
                clockA, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), storeA);
        engineA.start();
        List<MarketPhase> runA = drive(engineA, clockA, sequence);

        MutableClock clockB = EngineFixtures.clockAt("2026-10-05T09:00:00Z");
        InMemoryEventStore storeB = new InMemoryEventStore();
        TradingSessionEngine engineB = new TradingSessionEngine(
                clockB, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), storeB);
        engineB.start();
        List<MarketPhase> runB = drive(engineB, clockB, sequence);

        assertEquals(runA, runB);
        assertEquals(storeA.readAll().size(), storeB.readAll().size());
    }

    @Test
    void closingCancelsAllRestingOrders() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());
        engine.onOrder(new com.github.guoyyit.tradingsessionengine.model.OrderRequest(
                "o1", EngineFixtures.INSTRUMENT, new java.math.BigDecimal("10.00"), 100));

        clock.setTime(Instant.parse("2026-10-05T15:00:00Z"));
        engine.tick();

        MarketSnapshot snapshot = engine.snapshot();
        assertEquals(MarketPhase.CLOSED, snapshot.phase());
        assertEquals(0, snapshot.instruments().get(0).restingOrderCount());
    }
}

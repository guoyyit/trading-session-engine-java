package com.github.guoyyit.tradingsessionengine.tests.engine;

import com.github.guoyyit.tradingsessionengine.audit.InMemoryEventStore;
import com.github.guoyyit.tradingsessionengine.clock.MutableClock;
import com.github.guoyyit.tradingsessionengine.config.EngineConfig;
import com.github.guoyyit.tradingsessionengine.engine.TradingSessionEngine;
import com.github.guoyyit.tradingsessionengine.model.Decision;
import com.github.guoyyit.tradingsessionengine.model.HaltPolicy;
import com.github.guoyyit.tradingsessionengine.model.HaltReason;
import com.github.guoyyit.tradingsessionengine.model.InstrumentSnapshot;
import com.github.guoyyit.tradingsessionengine.model.InstrumentStatus;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;
import com.github.guoyyit.tradingsessionengine.model.OrderRequest;
import com.github.guoyyit.tradingsessionengine.model.RejectReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentHaltTest {

    private OrderRequest order(String id, String price) {
        return new OrderRequest(id, EngineFixtures.INSTRUMENT, new BigDecimal(price), 100);
    }

    @Test
    void manualHaltCancelsRestingOrdersAndRejectsIncomingByDefault() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());

        assertEquals(OrderAction.ACCEPT, engine.onOrder(order("o1", "10.00")).action());
        engine.halt(EngineFixtures.INSTRUMENT, HaltReason.MANUAL);

        InstrumentSnapshot snapshot = engine.snapshot().instruments().get(0);
        assertEquals(InstrumentStatus.HALTED, snapshot.status());
        assertEquals(HaltReason.MANUAL, snapshot.haltReason());
        assertEquals(0, snapshot.restingOrderCount());

        Decision incoming = engine.onOrder(order("o2", "10.00"));
        assertEquals(OrderAction.REJECT, incoming.action());
        assertEquals(RejectReason.INSTRUMENT_HALTED, incoming.reason());
    }

    @Test
    void queueStrategyParksOrdersAndResumeReleasesThemInContinuousTrading() {
        EngineConfig config = EngineFixtures.config(
                new HaltPolicy(OrderAction.REJECT, OrderAction.QUEUE, OrderAction.ACCEPT), (Path) null);
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, config);

        engine.halt(EngineFixtures.INSTRUMENT, HaltReason.MANUAL);
        Decision queued = engine.onOrder(order("q1", "10.00"));
        assertEquals(OrderAction.QUEUE, queued.action());
        assertEquals(1, engine.snapshot().instruments().get(0).queuedOrderIds().size());

        engine.resume(EngineFixtures.INSTRUMENT);
        InstrumentSnapshot after = engine.snapshot().instruments().get(0);
        assertEquals(InstrumentStatus.ACTIVE, after.status());
        assertEquals(1, after.restingOrderCount());
        assertTrue(after.queuedOrderIds().isEmpty());
    }

    @Test
    void keepRestingStrategyLeavesOrdersInPlaceThroughHalt() {
        EngineConfig config = EngineFixtures.config(
                new HaltPolicy(OrderAction.ACCEPT, OrderAction.REJECT, OrderAction.ACCEPT), (Path) null);
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, config);

        engine.onOrder(order("o1", "10.00"));
        engine.halt(EngineFixtures.INSTRUMENT, HaltReason.MANUAL);
        assertEquals(1, engine.snapshot().instruments().get(0).restingOrderCount());

        engine.resume(EngineFixtures.INSTRUMENT);
        assertEquals(1, engine.snapshot().instruments().get(0).restingOrderCount());
    }

    @Test
    void ruleHaltFiresOnStaticPriceViolationWhenConfigured() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = new TradingSessionEngine(
                clock, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), store);
        engine.start();
        engine.registerInstrument(EngineFixtures.INSTRUMENT, new BigDecimal("10.00"), true, false);
        engine.tick();

        // static band is [9.00, 11.00]; 12.00 breaches it and must rule-halt.
        Decision decision = engine.onOrder(order("bad", "12.00"));
        assertEquals(OrderAction.REJECT, decision.action());
        assertEquals(RejectReason.PRICE_OUTSIDE_STATIC_BAND, decision.reason());
        assertEquals(InstrumentStatus.HALTED,
                engine.snapshot().instruments().get(0).status());
        assertEquals(HaltReason.RULE_STATIC_PRICE,
                engine.snapshot().instruments().get(0).haltReason());
    }

    @Test
    void cancelDuringHaltFollowsConfiguredCancelStrategy() {
        EngineConfig config = EngineFixtures.config(
                new HaltPolicy(OrderAction.ACCEPT, OrderAction.REJECT, OrderAction.ACCEPT), (Path) null);
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, config);

        engine.onOrder(order("o1", "10.00"));
        engine.halt(EngineFixtures.INSTRUMENT, HaltReason.MANUAL);
        Decision cancelled = engine.onCancel(
                new com.github.guoyyit.tradingsessionengine.model.CancelRequest(
                        "o1", EngineFixtures.INSTRUMENT));
        assertEquals(OrderAction.ACCEPT, cancelled.action());
        assertEquals(0, engine.snapshot().instruments().get(0).restingOrderCount());
    }
}

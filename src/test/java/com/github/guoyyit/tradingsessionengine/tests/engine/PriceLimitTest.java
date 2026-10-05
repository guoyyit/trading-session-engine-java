package com.github.guoyyit.tradingsessionengine.tests.engine;

import com.github.guoyyit.tradingsessionengine.audit.InMemoryEventStore;
import com.github.guoyyit.tradingsessionengine.clock.MutableClock;
import com.github.guoyyit.tradingsessionengine.engine.PriceLimitService;
import com.github.guoyyit.tradingsessionengine.engine.TradingSessionEngine;
import com.github.guoyyit.tradingsessionengine.model.Decision;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;
import com.github.guoyyit.tradingsessionengine.model.OrderRequest;
import com.github.guoyyit.tradingsessionengine.model.PriceBand;
import com.github.guoyyit.tradingsessionengine.model.PriceBandType;
import com.github.guoyyit.tradingsessionengine.model.QuoteTick;
import com.github.guoyyit.tradingsessionengine.model.RejectReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceLimitTest {

    private OrderRequest order(String id, String price) {
        return new OrderRequest(id, EngineFixtures.INSTRUMENT, new BigDecimal(price), 100);
    }

    @Test
    void staticBandRejectsOutsidePricesAndAcceptsWithin() {
        PriceLimitService service = new PriceLimitService(EngineFixtures.defaultConfig());
        PriceBand band = service.buildBand(PriceBandType.STATIC, new BigDecimal("10.00"), 1);
        assertEquals(new BigDecimal("9.0000"), band.lower());
        assertEquals(new BigDecimal("11.0000"), band.upper());
        assertTrue(service.isWithin(band, new BigDecimal("9.00")));
        assertTrue(service.isWithin(band, new BigDecimal("11.00")));
        assertFalse(service.isWithin(band, new BigDecimal("8.99")));
        assertFalse(service.isWithin(band, new BigDecimal("11.01")));
    }

    @Test
    void engineRejectsOrdersOutsideStaticBand() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());

        assertEquals(OrderAction.REJECT, engine.onOrder(order("low", "8.00")).action());
        assertEquals(OrderAction.ACCEPT, engine.onOrder(order("ok", "10.50")).action());

        Decision high = engine.onOrder(order("high", "13.00"));
        assertEquals(OrderAction.REJECT, high.action());
        assertEquals(RejectReason.PRICE_OUTSIDE_STATIC_BAND, high.reason());
    }

    @Test
    void movingDynamicBandVersionsDecisionsWithoutAmbiguity() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());

        engine.onQuote(new QuoteTick(EngineFixtures.INSTRUMENT, new BigDecimal("10.00"), null, clock.now()));
        Decision insideOld = engine.onOrder(order("a", "10.20"));
        assertEquals(OrderAction.ACCEPT, insideOld.action());
        long bandVersionAtAccept = insideOld.dynamicBandVersion();

        clock.setTime(java.time.Instant.parse("2026-10-05T10:05:00Z"));
        engine.onQuote(new QuoteTick(EngineFixtures.INSTRUMENT, new BigDecimal("10.50"), null, clock.now()));

        Decision insideNew = engine.onOrder(order("b", "10.30"));
        assertEquals(OrderAction.ACCEPT, insideNew.action());
        assertTrue(insideNew.dynamicBandVersion() > bandVersionAtAccept);

        Decision nowOutside = engine.onOrder(order("c", "10.10"));
        assertEquals(OrderAction.REJECT, nowOutside.action());
        assertEquals(RejectReason.PRICE_OUTSIDE_DYNAMIC_BAND, nowOutside.reason());

        assertEquals(2, engine.snapshot().instruments().get(0).restingOrderCount());
    }

    @Test
    void dynamicViolationRejectsWhenNoRuleHaltConfigured() {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        InMemoryEventStore store = new InMemoryEventStore();
        TradingSessionEngine engine = EngineFixtures.startedEngine(clock, store, EngineFixtures.defaultConfig());
        engine.onQuote(new QuoteTick(EngineFixtures.INSTRUMENT, new BigDecimal("10.00"), null, clock.now()));

        Decision decision = engine.onOrder(order("x", "11.00"));
        assertEquals(OrderAction.REJECT, decision.action());
        assertEquals(RejectReason.PRICE_OUTSIDE_DYNAMIC_BAND, decision.reason());
    }
}

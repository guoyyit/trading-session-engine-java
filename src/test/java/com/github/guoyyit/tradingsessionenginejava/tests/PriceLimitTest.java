package com.github.guoyyit.tradingsessionenginejava.tests;

import com.github.guoyyit.tradingsessionenginejava.audit.InMemoryAuditLog;
import com.github.guoyyit.tradingsessionenginejava.clock.ManualClock;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineConfig;
import com.github.guoyyit.tradingsessionenginejava.engine.TradingStateEngine;
import com.github.guoyyit.tradingsessionenginejava.order.DecisionRecord;
import com.github.guoyyit.tradingsessionenginejava.order.OrderDecision;
import com.github.guoyyit.tradingsessionenginejava.order.OrderRequest;
import com.github.guoyyit.tradingsessionenginejava.pricelimit.PriceBand;
import com.github.guoyyit.tradingsessionenginejava.pricelimit.PriceLimitRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态/动态价格限制：价格带计算、越限拒绝、带移动后判定确定性。
 */
class PriceLimitTest {

    private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");

    private TradingStateEngine engineInContinuous(ManualClock clock, InMemoryAuditLog auditLog) {
        TradingStateEngine engine = new TradingStateEngine(
                clock, EngineConfig.defaults(), SessionStateMachineTest.standardDay(), auditLog, List.of());
        clock.set(T0.plusSeconds(3600 * 3));
        engine.tick();
        return engine;
    }

    private static OrderRequest order(String id, String instrument, String price) {
        return new OrderRequest(id, instrument, OrderRequest.Side.BUY, new BigDecimal(price), 100);
    }

    @Test
    void staticBandRejectsOutOfLimitOrders() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, auditLog);

        engine.setPriceLimitRule("AAA", new PriceLimitRule(
                PriceLimitRule.Kind.STATIC, new BigDecimal("0.10")));
        engine.setStaticReferencePrice("AAA", new BigDecimal("100"));

        assertEquals(OrderDecision.ACCEPTED, engine.submitOrder(order("o1", "AAA", "110.00")).decision());
        assertEquals(OrderDecision.ACCEPTED, engine.submitOrder(order("o2", "AAA", "90.00")).decision());

        DecisionRecord tooHigh = engine.submitOrder(order("o3", "AAA", "110.01"));
        assertEquals(OrderDecision.REJECTED, tooHigh.decision());
        assertEquals("price-out-of-band", tooHigh.reason());
        assertEquals(OrderDecision.REJECTED, engine.submitOrder(order("o4", "AAA", "89.99")).decision());
    }

    @Test
    void dynamicBandMovesWithTradePrice() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, auditLog);

        engine.setPriceLimitRule("AAA", new PriceLimitRule(
                PriceLimitRule.Kind.DYNAMIC, new BigDecimal("0.05")));
        engine.setStaticReferencePrice("AAA", new BigDecimal("100"));

        // 初始带 [95, 105]
        PriceBand initial = engine.snapshot().priceBands().get("AAA");
        assertEquals(0, new BigDecimal("95.00").compareTo(initial.lower()));
        assertEquals(0, new BigDecimal("105.00").compareTo(initial.upper()));

        // 行情移动到 200，带随之移动到 [190, 210]
        engine.onTradePrice("AAA", new BigDecimal("200"));
        PriceBand moved = engine.snapshot().priceBands().get("AAA");
        assertEquals(0, new BigDecimal("190.00").compareTo(moved.lower()));
        assertEquals(0, new BigDecimal("210.00").compareTo(moved.upper()));

        // 移动后：旧带内的价格（100）越限，新带内的价格（205）接受
        assertEquals(OrderDecision.REJECTED, engine.submitOrder(order("o1", "AAA", "100")).decision());
        assertEquals(OrderDecision.ACCEPTED, engine.submitOrder(order("o2", "AAA", "205")).decision());
    }

    @Test
    void bandChangeDoesNotAffectAlreadyAcceptedOrders() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, auditLog);

        engine.setPriceLimitRule("AAA", new PriceLimitRule(
                PriceLimitRule.Kind.DYNAMIC, new BigDecimal("0.05")));
        engine.setStaticReferencePrice("AAA", new BigDecimal("100"));

        // 订单在接受时判定为合法
        assertEquals(OrderDecision.ACCEPTED, engine.submitOrder(order("o1", "AAA", "100")).decision());

        // 价格带移动后该订单价格越限，但已接受订单仍可正常撤单（判定结果不翻案）
        engine.onTradePrice("AAA", new BigDecimal("200"));
        assertEquals(OrderDecision.ACCEPTED,
                engine.submitCancel(new com.github.guoyyit.tradingsessionenginejava.order.CancelRequest("o1", "AAA")).decision());
    }

    @Test
    void noRuleAllowsAnyPrice() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, auditLog);

        assertTrue(engine.snapshot().priceBands().isEmpty());
        assertEquals(OrderDecision.ACCEPTED, engine.submitOrder(order("o1", "AAA", "999999")).decision());
    }
}

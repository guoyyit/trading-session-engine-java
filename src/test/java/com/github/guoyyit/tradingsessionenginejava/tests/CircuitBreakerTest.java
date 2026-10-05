package com.github.guoyyit.tradingsessionenginejava.tests;

import com.github.guoyyit.tradingsessionenginejava.audit.InMemoryAuditLog;
import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerLevel;
import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerPhase;
import com.github.guoyyit.tradingsessionenginejava.clock.ManualClock;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineConfig;
import com.github.guoyyit.tradingsessionenginejava.engine.TradingStateEngine;
import com.github.guoyyit.tradingsessionenginejava.order.DecisionRecord;
import com.github.guoyyit.tradingsessionenginejava.order.OrderDecision;
import com.github.guoyyit.tradingsessionenginejava.order.OrderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 市场熔断：分级触发、冷却期请求处理、必须经冷却与恢复确认才能回到连续交易。
 */
class CircuitBreakerTest {

    private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");
    private static final List<CircuitBreakerLevel> LEVELS = List.of(
            new CircuitBreakerLevel(1, new BigDecimal("0.07"), Duration.ofMinutes(15)),
            new CircuitBreakerLevel(2, new BigDecimal("0.13"), Duration.ofMinutes(30)));

    private TradingStateEngine engineInContinuous(ManualClock clock, InMemoryAuditLog auditLog) {
        TradingStateEngine engine = new TradingStateEngine(
                clock, EngineConfig.defaults(), SessionStateMachineTest.standardDay(), auditLog, LEVELS);
        clock.set(T0.plusSeconds(3600 * 3));
        engine.tick();
        engine.setMarketReference(new BigDecimal("1000"));
        return engine;
    }

    private static OrderRequest order(String id) {
        return new OrderRequest(id, "AAA", OrderRequest.Side.BUY, new BigDecimal("10.00"), 100);
    }

    @Test
    void levelOneTriggerRejectsOrdersDuringCooling() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, auditLog);

        engine.onMarketIndex(new BigDecimal("920")); // 跌 8%，触发 1 级
        assertEquals(CircuitBreakerPhase.COOLING, engine.snapshot().circuitBreakerPhase());
        assertEquals(1, engine.snapshot().circuitBreakerLevel());

        DecisionRecord record = engine.submitOrder(order("o1"));
        assertEquals(OrderDecision.REJECTED, record.decision());
        assertEquals("circuit-breaker-cooling", record.reason());
    }

    @Test
    void higherLevelEscalatesWithLongerCooling() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, auditLog);

        engine.onMarketIndex(new BigDecimal("860")); // 跌 14%，直接触发 2 级
        assertEquals(2, engine.snapshot().circuitBreakerLevel());
        assertEquals(T0.plusSeconds(3600 * 3).plus(Duration.ofMinutes(30)),
                engine.circuitBreakerManager().coolingEndsAt().orElseThrow());
    }

    @Test
    void recoveryMustPassThroughCoolingAndResuming() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, auditLog);

        engine.onMarketIndex(new BigDecimal("900")); // 触发 1 级，冷却 15 分钟
        assertEquals(CircuitBreakerPhase.COOLING, engine.snapshot().circuitBreakerPhase());

        // 冷却期内确认恢复无效
        engine.confirmCircuitBreakerResume();
        assertEquals(CircuitBreakerPhase.COOLING, engine.snapshot().circuitBreakerPhase());

        // 冷却未结束，tick 不会提前进入 RESUMING
        clock.advanceMillis(10 * 60 * 1000);
        engine.tick();
        assertEquals(CircuitBreakerPhase.COOLING, engine.snapshot().circuitBreakerPhase());

        // 冷却结束 → RESUMING（不是直接回 NONE）
        clock.advanceMillis(5 * 60 * 1000);
        engine.tick();
        assertEquals(CircuitBreakerPhase.RESUMING, engine.snapshot().circuitBreakerPhase());

        // RESUMING 期间订单仍被拒绝
        assertEquals(OrderDecision.REJECTED, engine.submitOrder(order("o1")).decision());

        // 确认恢复 → NONE，订单恢复接受
        engine.confirmCircuitBreakerResume();
        assertEquals(CircuitBreakerPhase.NONE, engine.snapshot().circuitBreakerPhase());
        assertEquals(0, engine.snapshot().circuitBreakerLevel());
        assertEquals(OrderDecision.ACCEPTED, engine.submitOrder(order("o2")).decision());
    }

    @Test
    void noTriggerBelowThreshold() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, auditLog);

        engine.onMarketIndex(new BigDecimal("950")); // 跌 5%，未达阈值
        assertEquals(CircuitBreakerPhase.NONE, engine.snapshot().circuitBreakerPhase());
        assertEquals(OrderDecision.ACCEPTED, engine.submitOrder(order("o1")).decision());
    }
}

package com.github.guoyyit.tradingsessionenginejava.tests;

import com.github.guoyyit.tradingsessionenginejava.audit.InMemoryAuditLog;
import com.github.guoyyit.tradingsessionenginejava.clock.ManualClock;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineConfig;
import com.github.guoyyit.tradingsessionenginejava.engine.TradingStateEngine;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltPolicies;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltTrigger;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltedCancelPolicy;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltedOrderPolicy;
import com.github.guoyyit.tradingsessionenginejava.instrument.InstrumentTradingState;
import com.github.guoyyit.tradingsessionenginejava.instrument.RestingOrderAction;
import com.github.guoyyit.tradingsessionenginejava.order.CancelRequest;
import com.github.guoyyit.tradingsessionenginejava.order.DecisionRecord;
import com.github.guoyyit.tradingsessionenginejava.order.OrderDecision;
import com.github.guoyyit.tradingsessionenginejava.order.OrderRequest;
import com.github.guoyyit.tradingsessionenginejava.session.SessionPhase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 品种停牌/复牌：人工与规则触发、存量订单处置、停牌期间策略、复牌无残留。
 */
class InstrumentHaltTest {

    private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");

    private TradingStateEngine engineInContinuous(ManualClock clock, EngineConfig config, InMemoryAuditLog auditLog) {
        TradingStateEngine engine = new TradingStateEngine(
                clock, config, SessionStateMachineTest.standardDay(), auditLog, List.of());
        clock.set(T0.plusSeconds(3600 * 3)); // 连续交易开始
        engine.tick();
        return engine;
    }

    private static OrderRequest order(String id, String instrument) {
        return new OrderRequest(id, instrument, OrderRequest.Side.BUY, new BigDecimal("10.00"), 100);
    }

    @Test
    void manualHaltRejectsNewOrdersWithRejectPolicy() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, EngineConfig.defaults(), auditLog);

        engine.haltInstrument("AAA", HaltTrigger.MANUAL);
        DecisionRecord record = engine.submitOrder(order("o1", "AAA"));
        assertEquals(OrderDecision.REJECTED, record.decision());
        assertEquals("instrument-halted", record.reason());
        assertEquals(InstrumentTradingState.HALTED, engine.snapshot().instrumentStates().get("AAA"));
    }

    @Test
    void ruleBasedHaltCancelsRestingOrders() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = engineInContinuous(clock, EngineConfig.defaults(), auditLog);

        engine.submitOrder(order("o1", "AAA"));
        engine.submitOrder(order("o2", "AAA"));
        engine.submitOrder(order("o3", "BBB"));

        engine.haltInstrument("AAA", HaltTrigger.RULE_BASED);

        // CANCEL_ALL：AAA 的存量订单被撤销，撤单返回 unknown-order；BBB 不受影响
        assertEquals(OrderDecision.REJECTED, engine.submitCancel(new CancelRequest("o1", "AAA")).decision());
        assertEquals(OrderDecision.ACCEPTED, engine.submitCancel(new CancelRequest("o3", "BBB")).decision());
    }

    @Test
    void keepPolicyRetainsRestingOrders() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        EngineConfig config = EngineConfig.builder()
                .haltPolicies(new HaltPolicies(
                        RestingOrderAction.KEEP, HaltedOrderPolicy.REJECT, HaltedCancelPolicy.ACCEPT))
                .build();
        TradingStateEngine engine = engineInContinuous(clock, config, auditLog);

        engine.submitOrder(order("o1", "AAA"));
        engine.haltInstrument("AAA", HaltTrigger.MANUAL);

        // KEEP：存量订单保留，停牌期间允许撤单
        assertEquals(OrderDecision.ACCEPTED, engine.submitCancel(new CancelRequest("o1", "AAA")).decision());
    }

    @Test
    void haltedCancelRejectPolicy() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        EngineConfig config = EngineConfig.builder()
                .haltPolicies(new HaltPolicies(
                        RestingOrderAction.KEEP, HaltedOrderPolicy.REJECT, HaltedCancelPolicy.REJECT))
                .build();
        TradingStateEngine engine = engineInContinuous(clock, config, auditLog);

        engine.submitOrder(order("o1", "AAA"));
        engine.haltInstrument("AAA", HaltTrigger.MANUAL);
        DecisionRecord record = engine.submitCancel(new CancelRequest("o1", "AAA"));
        assertEquals(OrderDecision.REJECTED, record.decision());
        assertEquals("instrument-halted", record.reason());
    }

    @Test
    void queuedOrdersAcceptedIntoResumedPhaseWithoutResidue() {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        EngineConfig config = EngineConfig.builder()
                .haltPolicies(new HaltPolicies(
                        RestingOrderAction.KEEP, HaltedOrderPolicy.QUEUE, HaltedCancelPolicy.ACCEPT))
                .build();
        TradingStateEngine engine = engineInContinuous(clock, config, auditLog);

        engine.haltInstrument("AAA", HaltTrigger.MANUAL);
        DecisionRecord queued = engine.submitOrder(order("o1", "AAA"));
        assertEquals(OrderDecision.QUEUED, queued.decision());
        assertEquals(SessionPhase.CONTINUOUS_TRADING, queued.phase());

        // 复牌前推进到收盘集合竞价，复牌后排队订单归属复牌时刻的时段
        clock.set(T0.plusSeconds(3600 * 8));
        engine.resumeInstrument("AAA");

        assertFalse(engine.snapshot().instrumentStates().containsKey("AAA"));
        // 复牌后排队订单被接受且归属当前（复牌时）时段
        var decisions = auditLog.readAll().stream()
                .filter(e -> e.type() == com.github.guoyyit.tradingsessionenginejava.audit.AuditEventType.ORDER_DECIDED)
                .filter(e -> e.payload().get("orderId").equals("o1"))
                .toList();
        assertEquals(2, decisions.size());
        assertEquals(OrderDecision.QUEUED.name(), decisions.get(0).payload().get("decision"));
        assertEquals(OrderDecision.ACCEPTED.name(), decisions.get(1).payload().get("decision"));
        assertEquals(SessionPhase.CLOSING_AUCTION.name(), decisions.get(1).payload().get("phase"));
        assertEquals("resumed-from-queue", decisions.get(1).payload().get("reason"));

        // 复牌后新订单正常接受，无状态残留
        DecisionRecord after = engine.submitOrder(order("o2", "AAA"));
        assertEquals(OrderDecision.ACCEPTED, after.decision());
        assertEquals(SessionPhase.CLOSING_AUCTION, after.phase());
    }
}

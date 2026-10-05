package com.github.guoyyit.tradingsessionenginejava.tests;

import com.github.guoyyit.tradingsessionenginejava.audit.InMemoryAuditLog;
import com.github.guoyyit.tradingsessionenginejava.clock.ManualClock;
import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerPhase;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineConfig;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineSnapshot;
import com.github.guoyyit.tradingsessionenginejava.engine.TradingStateEngine;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltTrigger;
import com.github.guoyyit.tradingsessionenginejava.order.DecisionRecord;
import com.github.guoyyit.tradingsessionenginejava.order.OrderDecision;
import com.github.guoyyit.tradingsessionenginejava.order.OrderRequest;
import com.github.guoyyit.tradingsessionenginejava.session.SessionPhase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发：快照读取自洽；阶段切换瞬间请求归属唯一确定。
 */
class ConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");

    @Test
    void concurrentSnapshotsAreAlwaysCoherent() throws InterruptedException {
        ManualClock clock = new ManualClock(T0);
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = new TradingStateEngine(
                clock, EngineConfig.defaults(), SessionStateMachineTest.standardDay(), auditLog, List.of());

        int readerCount = 4;
        int readsPerReader = 500;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger();

        for (int i = 0; i < readerCount; i++) {
            pool.submit(() -> {
                await(start);
                for (int j = 0; j < readsPerReader; j++) {
                    EngineSnapshot snapshot = engine.snapshot();
                    if (!isCoherent(snapshot)) {
                        violations.incrementAndGet();
                    }
                }
            });
        }
        pool.submit(() -> {
            await(start);
            for (int j = 0; j < 200; j++) {
                clock.advanceMillis(60_000);
                engine.tick();
                engine.haltInstrument("I" + (j % 5), HaltTrigger.MANUAL);
                engine.resumeInstrument("I" + (j % 5));
                engine.submitOrder(new OrderRequest("o" + j, "AAA",
                        OrderRequest.Side.BUY, new BigDecimal("10"), 1));
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, violations.get(), "snapshot must be internally coherent");
    }

    private static boolean isCoherent(EngineSnapshot snapshot) {
        if (snapshot.snapshotAt() == null || snapshot.sessionPhase() == null
                || snapshot.circuitBreakerPhase() == null
                || snapshot.instrumentStates() == null || snapshot.priceBands() == null) {
            return false;
        }
        // NONE 阶段级别必须为 0；COOLING/RESUMING 阶段级别必须为正
        if (snapshot.circuitBreakerPhase() == CircuitBreakerPhase.NONE) {
            return snapshot.circuitBreakerLevel() == 0;
        }
        return snapshot.circuitBreakerLevel() > 0;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void requestAtPhaseBoundaryHasUniqueDeterministicOwner() {
        // 相同的时钟序列与请求序列，重复执行得到逐次一致的归属
        List<SessionPhase> run1 = boundaryRun();
        List<SessionPhase> run2 = boundaryRun();
        assertEquals(run1, run2);
        // 边界前的请求归属连续交易，边界后的归属收盘集合竞价，不存在歧义
        assertEquals(SessionPhase.CONTINUOUS_TRADING, run1.get(0));
        assertEquals(SessionPhase.CLOSING_AUCTION, run1.get(1));
    }

    private List<SessionPhase> boundaryRun() {
        ManualClock clock = new ManualClock(T0.plusSeconds(3600 * 8 - 1));
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = new TradingStateEngine(
                clock, EngineConfig.defaults(), SessionStateMachineTest.standardDay(), auditLog, List.of());
        engine.tick();

        DecisionRecord before = engine.submitOrder(new OrderRequest("before", "AAA",
                OrderRequest.Side.BUY, new BigDecimal("10"), 1));
        clock.set(T0.plusSeconds(3600 * 8));
        DecisionRecord after = engine.submitOrder(new OrderRequest("after", "AAA",
                OrderRequest.Side.BUY, new BigDecimal("10"), 1));
        assertNotNull(before);
        return List.of(before.phase(), after.phase());
    }

    @Test
    void decisionInAuctionRespectsConfig() {
        ManualClock clock = new ManualClock(T0.plusSeconds(3600 * 2));
        InMemoryAuditLog auditLog = new InMemoryAuditLog(clock);
        TradingStateEngine engine = new TradingStateEngine(
                clock, EngineConfig.defaults(), SessionStateMachineTest.standardDay(), auditLog, List.of());
        engine.tick();
        assertEquals(SessionPhase.OPENING_AUCTION, engine.snapshot().sessionPhase());
        // 默认配置：集合竞价接受订单
        assertEquals(OrderDecision.ACCEPTED, engine.submitOrder(new OrderRequest("o1", "AAA",
                OrderRequest.Side.BUY, new BigDecimal("10"), 1)).decision());

        // 休市时段拒绝
        clock.set(T0.plusSeconds(3600 * 5));
        DecisionRecord inBreak = engine.submitOrder(new OrderRequest("o2", "AAA",
                OrderRequest.Side.BUY, new BigDecimal("10"), 1));
        assertEquals(OrderDecision.REJECTED, inBreak.decision());
        assertEquals("session-break", inBreak.reason());
    }
}

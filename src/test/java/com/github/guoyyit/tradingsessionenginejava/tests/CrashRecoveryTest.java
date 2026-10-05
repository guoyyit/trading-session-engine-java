package com.github.guoyyit.tradingsessionenginejava.tests;

import com.github.guoyyit.tradingsessionenginejava.audit.FileAuditLog;
import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerLevel;
import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerPhase;
import com.github.guoyyit.tradingsessionenginejava.clock.ManualClock;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineConfig;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineSnapshot;
import com.github.guoyyit.tradingsessionenginejava.engine.TradingStateEngine;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltTrigger;
import com.github.guoyyit.tradingsessionenginejava.instrument.InstrumentTradingState;
import com.github.guoyyit.tradingsessionenginejava.order.OrderRequest;
import com.github.guoyyit.tradingsessionenginejava.persistence.StateRestorer;
import com.github.guoyyit.tradingsessionenginejava.pricelimit.PriceLimitRule;
import com.github.guoyyit.tradingsessionenginejava.session.SessionPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃恢复：审计日志持久化，重启后重放重建崩溃前状态，且与在线状态一致。
 */
class CrashRecoveryTest {

    private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");
    private static final List<CircuitBreakerLevel> LEVELS = List.of(
            new CircuitBreakerLevel(1, new BigDecimal("0.07"), Duration.ofMinutes(15)));

    @TempDir
    Path tempDir;

    private TradingStateEngine newEngine(ManualClock clock, FileAuditLog auditLog) {
        return new TradingStateEngine(clock, EngineConfig.defaults(),
                SessionStateMachineTest.standardDay(), auditLog, LEVELS);
    }

    @Test
    void replayRebuildsPreCrashState() {
        Path auditFile = tempDir.resolve("audit.log");
        ManualClock clock = new ManualClock(T0);
        FileAuditLog auditLog = new FileAuditLog(clock, auditFile);
        TradingStateEngine engine = newEngine(clock, auditLog);

        // 制造一系列状态变化
        clock.set(T0.plusSeconds(3600 * 2));
        engine.tick(); // 开盘集合竞价
        clock.set(T0.plusSeconds(3600 * 3));
        engine.tick(); // 连续交易
        engine.setPriceLimitRule("AAA", new PriceLimitRule(
                PriceLimitRule.Kind.STATIC, new BigDecimal("0.10")));
        engine.setStaticReferencePrice("AAA", new BigDecimal("100"));
        engine.submitOrder(new OrderRequest("o1", "AAA", OrderRequest.Side.BUY,
                new BigDecimal("100"), 100));
        engine.haltInstrument("BBB", HaltTrigger.MANUAL);
        engine.setMarketReference(new BigDecimal("1000"));
        engine.onMarketIndex(new BigDecimal("900")); // 触发 1 级熔断

        EngineSnapshot online = engine.snapshot();

        // 模拟崩溃重启：新实例从审计文件重放
        StateRestorer restorer = new StateRestorer(new FileAuditLog(clock, auditFile));
        EngineSnapshot restored = restorer.restore().orElseThrow();

        assertEquals(online.sessionPhase(), restored.sessionPhase());
        assertEquals(online.circuitBreakerPhase(), restored.circuitBreakerPhase());
        assertEquals(online.circuitBreakerLevel(), restored.circuitBreakerLevel());
        assertEquals(online.instrumentStates(), restored.instrumentStates());
        assertEquals(online.priceBands(), restored.priceBands());
        assertEquals(online.lastAuditSequence(), restored.lastAuditSequence());
    }

    @Test
    void replayToRebuildsStateAtAnyPoint() {
        Path auditFile = tempDir.resolve("audit.log");
        ManualClock clock = new ManualClock(T0);
        FileAuditLog auditLog = new FileAuditLog(clock, auditFile);
        TradingStateEngine engine = newEngine(clock, auditLog);

        clock.set(T0.plusSeconds(3600 * 3));
        engine.tick();
        engine.haltInstrument("AAA", HaltTrigger.RULE_BASED);
        long seqAfterHalt = auditLog.lastSequence();
        engine.resumeInstrument("AAA");
        engine.haltInstrument("CCC", HaltTrigger.MANUAL);

        StateRestorer restorer = new StateRestorer(new FileAuditLog(clock, auditFile));

        // 重放到停牌后、复牌前：AAA 处于停牌，CCC 尚未停牌
        EngineSnapshot atHalt = restorer.replayTo(seqAfterHalt);
        assertEquals(InstrumentTradingState.HALTED, atHalt.instrumentStates().get("AAA"));
        assertTrue(atHalt.instrumentStates().get("CCC") == null);

        // 重放到最后：AAA 已复牌（无残留），CCC 停牌中
        EngineSnapshot latest = restorer.restore().orElseThrow();
        assertTrue(latest.instrumentStates().get("AAA") == null);
        assertEquals(InstrumentTradingState.HALTED, latest.instrumentStates().get("CCC"));
        assertEquals(SessionPhase.CONTINUOUS_TRADING, latest.sessionPhase());
    }

    @Test
    void incompleteMigrationDoesNotTakeEffect() {
        Path auditFile = tempDir.resolve("audit.log");
        ManualClock clock = new ManualClock(T0);
        FileAuditLog auditLog = new FileAuditLog(clock, auditFile);
        TradingStateEngine engine = newEngine(clock, auditLog);

        clock.set(T0.plusSeconds(3600 * 3));
        engine.tick();
        engine.haltInstrument("AAA", HaltTrigger.MANUAL);
        long seqBefore = auditLog.lastSequence();

        // 模拟崩溃发生在迁移落审计之前：时钟推进但未执行任何已确认操作
        clock.set(T0.plusSeconds(3600 * 8));

        StateRestorer restorer = new StateRestorer(new FileAuditLog(clock, auditFile));
        EngineSnapshot restored = restorer.restore().orElseThrow();
        // 状态停留在最后一条已确认审计事件，未确认的时钟推进不生效
        assertEquals(seqBefore, restored.lastAuditSequence());
        assertEquals(SessionPhase.CONTINUOUS_TRADING, restored.sessionPhase());
        assertEquals(InstrumentTradingState.HALTED, restored.instrumentStates().get("AAA"));
    }

    @Test
    void circuitBreakerStateSurvivesRestart() {
        Path auditFile = tempDir.resolve("audit.log");
        ManualClock clock = new ManualClock(T0);
        FileAuditLog auditLog = new FileAuditLog(clock, auditFile);
        TradingStateEngine engine = newEngine(clock, auditLog);

        clock.set(T0.plusSeconds(3600 * 3));
        engine.tick();
        engine.setMarketReference(new BigDecimal("1000"));
        engine.onMarketIndex(new BigDecimal("900"));
        clock.advanceMillis(15 * 60 * 1000);
        engine.tick(); // 冷却结束 → RESUMING

        StateRestorer restorer = new StateRestorer(new FileAuditLog(clock, auditFile));
        EngineSnapshot restored = restorer.restore().orElseThrow();
        assertEquals(CircuitBreakerPhase.RESUMING, restored.circuitBreakerPhase());
        assertEquals(1, restored.circuitBreakerLevel());
    }
}

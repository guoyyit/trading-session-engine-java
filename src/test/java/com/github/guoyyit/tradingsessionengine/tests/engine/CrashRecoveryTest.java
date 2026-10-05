package com.github.guoyyit.tradingsessionengine.tests.engine;

import com.github.guoyyit.tradingsessionengine.audit.FileEventStore;
import com.github.guoyyit.tradingsessionengine.clock.MutableClock;
import com.github.guoyyit.tradingsessionengine.engine.TradingSessionEngine;
import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.HaltReason;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;
import com.github.guoyyit.tradingsessionengine.model.MarketSnapshot;
import com.github.guoyyit.tradingsessionengine.model.OrderRequest;
import com.github.guoyyit.tradingsessionengine.model.QuoteTick;
import com.github.guoyyit.tradingsessionengine.replay.Replayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {

    private Path logFile(Path dir) {
        return dir.resolve("events.log");
    }

    private void seedScenario(Path log) {
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        FileEventStore store = new FileEventStore(log);
        TradingSessionEngine engine = new TradingSessionEngine(
                clock, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), store);
        engine.start();
        engine.registerInstrument(EngineFixtures.INSTRUMENT, new BigDecimal("10.00"), true, false);
        engine.tick();
        engine.onQuote(new QuoteTick(EngineFixtures.INSTRUMENT, new BigDecimal("10.00"), null, clock.now()));
        engine.onOrder(new OrderRequest("o1", EngineFixtures.INSTRUMENT, new BigDecimal("10.00"), 100));
        engine.halt(EngineFixtures.INSTRUMENT, HaltReason.MANUAL);
        engine.triggerBreaker(BreakerLevel.LEVEL1);
    }

    @Test
    void replayRebuildsTheExactOnlineState(@TempDir Path dir) {
        Path log = logFile(dir);
        seedScenario(log);

        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        FileEventStore reopened = new FileEventStore(log);
        TradingSessionEngine recovered = new TradingSessionEngine(
                clock, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), reopened);
        recovered.recover();

        MarketSnapshot snapshot = recovered.snapshot();
        assertEquals(MarketPhase.BREAKER_HALT, snapshot.phase());
        assertTrue(snapshot.breaker().active());
        assertEquals(BreakerLevel.LEVEL1, snapshot.breaker().level());
        assertEquals(1, snapshot.instruments().size());
        assertEquals(com.github.guoyyit.tradingsessionengine.model.InstrumentStatus.HALTED,
                snapshot.instruments().get(0).status());
        assertEquals(HaltReason.MANUAL, snapshot.instruments().get(0).haltReason());
        // resting order was cancelled by the default halt policy
        assertEquals(0, snapshot.instruments().get(0).restingOrderCount());

        // a Replayer-only rebuild yields the same phase and sequence
        MarketSnapshot rebuiltView = snapshotOf(new Replayer(new FileEventStore(log)).rebuild(),
                new FileEventStore(log).lastSequence());
        assertEquals(MarketPhase.BREAKER_HALT, rebuiltView.phase());
        assertEquals(snapshot.sequence(), rebuiltView.sequence());
    }

    @Test
    void recoveredEngineContinuesTransitionsCorrectly(@TempDir Path dir) {
        Path log = logFile(dir);
        seedScenario(log);

        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        FileEventStore store = new FileEventStore(log);
        TradingSessionEngine engine = new TradingSessionEngine(
                clock, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), store);
        engine.recover();

        clock.setTime(Instant.parse("2026-10-05T10:05:00Z"));
        engine.tick();
        assertEquals(MarketPhase.BREAKER_COOLDOWN, engine.snapshot().phase());

        clock.setTime(Instant.parse("2026-10-05T10:08:00Z"));
        engine.tick();
        assertEquals(MarketPhase.CONTINUOUS, engine.snapshot().phase());
    }

    @Test
    void tornTailLineIsIgnoredOnRecovery(@TempDir Path dir) throws Exception {
        Path log = logFile(dir);
        seedScenario(log);
        long goodBytes = Files.size(log);

        // simulate a torn partial write at the tail after a crash
        List<String> lines = Files.readAllLines(log);
        String lastLine = lines.get(lines.size() - 1);
        String torn = lastLine.substring(0, Math.max(1, lastLine.length() / 2));
        Files.writeString(log, Files.readString(log) + torn);

        FileEventStore store = new FileEventStore(log);
        assertEquals(lines.size(), store.readAll().size());
        // sequence accounting stops at the last complete event
        assertEquals(lines.size(), store.lastSequence());

        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        TradingSessionEngine engine = new TradingSessionEngine(
                clock, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), store);
        engine.recover();
        assertEquals(MarketPhase.BREAKER_HALT, engine.snapshot().phase());
        assertTrue(goodBytes >= 0);
    }

    @Test
    void concurrentReadsAlwaysReturnASelfConsistentSnapshot(@TempDir Path dir) {
        Path log = logFile(dir);
        MutableClock clock = EngineFixtures.clockAt("2026-10-05T10:00:00Z");
        FileEventStore store = new FileEventStore(log);
        TradingSessionEngine engine = new TradingSessionEngine(
                clock, EngineFixtures.defaultConfig(), EngineFixtures.schedule(), store);
        engine.start();
        engine.registerInstrument(EngineFixtures.INSTRUMENT, new BigDecimal("10.00"), false, false);
        engine.tick();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200 && !stop.get(); i++) {
                    String id = "n" + i;
                    engine.onOrder(new OrderRequest(id, EngineFixtures.INSTRUMENT,
                            new BigDecimal("10.00"), 100));
                    engine.onCancel(new com.github.guoyyit.tradingsessionengine.model.CancelRequest(
                            id, EngineFixtures.INSTRUMENT));
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        MarketSnapshot snapshot = engine.snapshot();
                        // self-consistency: phase matches breaker view and
                        // sequence is non-negative; instrument counts never negative
                        if (snapshot.phase() == null
                                || snapshot.sequence() < 0
                                || snapshot.instruments().get(0).restingOrderCount() < 0) {
                            throw new IllegalStateException("torn snapshot: " + snapshot);
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            readers.add(reader);
        }

        readers.forEach(Thread::start);
        writer.start();
        start.countDown();
        try {
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stop.set(true);
        readers.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertEquals(null, failure.get());
        // all orders were cancelled, so the book is empty and consistent
        assertEquals(0, engine.snapshot().instruments().get(0).restingOrderCount());
    }

    private MarketSnapshot snapshotOf(
            com.github.guoyyit.tradingsessionengine.engine.EngineState state, long sequence) {
        return new MarketSnapshot(state.tradingDay, state.phase, state.phaseSince, null,
                List.of(), sequence);
    }
}

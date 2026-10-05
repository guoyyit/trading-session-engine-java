package com.github.guoyyit.tradingsessionengine.tests.engine;

import com.github.guoyyit.tradingsessionengine.audit.EventStore;
import com.github.guoyyit.tradingsessionengine.audit.InMemoryEventStore;
import com.github.guoyyit.tradingsessionengine.clock.MutableClock;
import com.github.guoyyit.tradingsessionengine.config.BreakerConfig;
import com.github.guoyyit.tradingsessionengine.config.BreakerLevelSpec;
import com.github.guoyyit.tradingsessionengine.config.EngineConfig;
import com.github.guoyyit.tradingsessionengine.config.SessionSchedule;
import com.github.guoyyit.tradingsessionengine.config.SessionSegment;
import com.github.guoyyit.tradingsessionengine.engine.TradingSessionEngine;
import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.HaltPolicy;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

/** Shared builders for deterministic, wall-clock-free engine tests. */
final class EngineFixtures {

    static final String INSTRUMENT = "600000";
    static final LocalDate DAY = LocalDate.of(2026, 10, 5);

    private EngineFixtures() {
    }

    static SessionSchedule schedule() {
        return new SessionSchedule(DAY, List.of(
                new SessionSegment(MarketPhase.OPEN_AUCTION, LocalTime.of(9, 15), LocalTime.of(9, 25)),
                new SessionSegment(MarketPhase.CONTINUOUS, LocalTime.of(9, 30), LocalTime.of(14, 57)),
                new SessionSegment(MarketPhase.CLOSE_AUCTION, LocalTime.of(14, 57), LocalTime.of(15, 0))),
                ZoneOffset.UTC);
    }

    static BreakerConfig breakerConfig() {
        return new BreakerConfig(List.of(
                new BreakerLevelSpec(BreakerLevel.LEVEL1, Duration.ofMinutes(5), Duration.ofMinutes(3)),
                new BreakerLevelSpec(BreakerLevel.LEVEL2, Duration.ofMinutes(10), Duration.ofMinutes(5)),
                new BreakerLevelSpec(BreakerLevel.LEVEL3, Duration.ofMinutes(15), Duration.ofMinutes(10)),
                new BreakerLevelSpec(BreakerLevel.CLOSE, Duration.ZERO, Duration.ZERO)));
    }

    static EngineConfig config(HaltPolicy haltPolicy, Path eventLog) {
        return new EngineConfig(new BigDecimal("0.10"), new BigDecimal("0.03"),
                haltPolicy, breakerConfig(), eventLog);
    }

    static EngineConfig defaultConfig() {
        return config(new HaltPolicy(OrderAction.REJECT, OrderAction.REJECT, OrderAction.ACCEPT), null);
    }

    static MutableClock clockAt(String iso) {
        return new MutableClock(java.time.Instant.parse(iso));
    }

    static TradingSessionEngine startedEngine(MutableClock clock, EventStore store, EngineConfig config) {
        TradingSessionEngine engine = new TradingSessionEngine(clock, config, schedule(), store);
        engine.start();
        engine.registerInstrument(INSTRUMENT, new BigDecimal("10.00"), false, false);
        engine.tick();
        return engine;
    }

    static TradingSessionEngine continuousEngine(MutableClock clock, EventStore store) {
        return startedEngine(clockAt("2026-10-05T10:00:00Z"), store, defaultConfig());
    }

    static long secondOfDay(String iso) {
        return java.time.Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay();
    }
}

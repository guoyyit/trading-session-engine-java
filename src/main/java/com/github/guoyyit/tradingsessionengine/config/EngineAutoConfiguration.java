package com.github.guoyyit.tradingsessionengine.config;

import com.github.guoyyit.tradingsessionengine.audit.EventStore;
import com.github.guoyyit.tradingsessionengine.audit.FileEventStore;
import com.github.guoyyit.tradingsessionengine.clock.Clock;
import com.github.guoyyit.tradingsessionengine.clock.SystemClock;
import com.github.guoyyit.tradingsessionengine.engine.TradingSessionEngine;
import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.HaltPolicy;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;
import com.github.guoyyit.tradingsessionengine.service.TradingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Default Spring wiring. Every tunable has a property override (see
 * application.properties / README); the clock defaults to the system clock
 * and can be replaced by a test bean.
 */
@Configuration
public class EngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return new SystemClock();
    }

    @Bean
    public EngineConfig engineConfig(
            @Value("${trading.price-band.static-percentage:0.10}") String staticPercentage,
            @Value("${trading.price-band.dynamic-percentage:0.03}") String dynamicPercentage,
            @Value("${trading.halt.resting-strategy:REJECT}") String restingStrategy,
            @Value("${trading.halt.incoming-order-strategy:REJECT}") String incomingOrderStrategy,
            @Value("${trading.halt.incoming-cancel-strategy:ACCEPT}") String incomingCancelStrategy,
            @Value("${trading.event-log:./data/trading-events.log}") String eventLog) {
        HaltPolicy haltPolicy = new HaltPolicy(
                OrderAction.valueOf(restingStrategy),
                OrderAction.valueOf(incomingOrderStrategy),
                OrderAction.valueOf(incomingCancelStrategy));
        return new EngineConfig(new BigDecimal(staticPercentage), new BigDecimal(dynamicPercentage),
                haltPolicy, defaultBreakerConfig(), Path.of(eventLog));
    }

    @Bean
    public SessionSchedule sessionSchedule() {
        LocalDate day = LocalDate.now();
        return new SessionSchedule(day, List.of(
                new SessionSegment(com.github.guoyyit.tradingsessionengine.model.MarketPhase.OPEN_AUCTION,
                        LocalTime.of(9, 15), LocalTime.of(9, 25)),
                new SessionSegment(com.github.guoyyit.tradingsessionengine.model.MarketPhase.CONTINUOUS,
                        LocalTime.of(9, 30), LocalTime.of(14, 57)),
                new SessionSegment(com.github.guoyyit.tradingsessionengine.model.MarketPhase.CLOSE_AUCTION,
                        LocalTime.of(14, 57), LocalTime.of(15, 0))),
                ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    @ConditionalOnMissingBean(EventStore.class)
    public EventStore eventStore(EngineConfig config) {
        return new FileEventStore(config.eventLogPath());
    }

    @Bean
    public TradingSessionEngine tradingSessionEngine(Clock clock, EngineConfig config,
                                                     SessionSchedule schedule, EventStore eventStore) {
        return new TradingSessionEngine(clock, config, schedule, eventStore);
    }

    @Bean
    public TradingService tradingService(TradingSessionEngine engine, Clock clock) {
        return new TradingService(engine, clock);
    }

    /** L1: 5m halt + 3m cooldown; L2: 10m + 5m; L3: 15m + 10m; CLOSE: terminal. */
    private BreakerConfig defaultBreakerConfig() {
        return new BreakerConfig(List.of(
                new BreakerLevelSpec(BreakerLevel.LEVEL1, Duration.ofMinutes(5), Duration.ofMinutes(3)),
                new BreakerLevelSpec(BreakerLevel.LEVEL2, Duration.ofMinutes(10), Duration.ofMinutes(5)),
                new BreakerLevelSpec(BreakerLevel.LEVEL3, Duration.ofMinutes(15), Duration.ofMinutes(10)),
                new BreakerLevelSpec(BreakerLevel.CLOSE, Duration.ZERO, Duration.ZERO)));
    }
}

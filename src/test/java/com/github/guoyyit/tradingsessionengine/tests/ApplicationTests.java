package com.github.guoyyit.tradingsessionengine.tests;

import com.github.guoyyit.tradingsessionengine.clock.Clock;
import com.github.guoyyit.tradingsessionengine.clock.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "trading.event-log=./target/test-data/trading-events.log"
})
class ApplicationTests {

    @TestConfiguration
    static class DeterministicInfrastructure {
        @Bean
        @Primary
        Clock clock() {
            return new MutableClock(Instant.parse("2026-10-05T09:00:00Z"));
        }
    }

    @Test
    void contextLoads() {
    }
}

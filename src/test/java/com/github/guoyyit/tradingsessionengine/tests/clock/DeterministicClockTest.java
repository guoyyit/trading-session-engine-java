package com.github.guoyyit.tradingsessionengine.tests.clock;

import com.github.guoyyit.tradingsessionengine.clock.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicClockTest {

    @Test
    void drivesAnExplicitTimeSequence() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T09:00:00Z"));
        assertEquals(Instant.parse("2026-10-05T09:00:00Z"), clock.now());

        clock.advanceMillis(60_000);
        assertEquals(Instant.parse("2026-10-05T09:01:00Z"), clock.now());

        clock.setTime(Instant.parse("2026-10-05T15:00:00Z"));
        assertEquals(Instant.parse("2026-10-05T15:00:00Z"), clock.now());
    }
}

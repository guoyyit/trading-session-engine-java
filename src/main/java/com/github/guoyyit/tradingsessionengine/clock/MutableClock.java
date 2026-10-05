package com.github.guoyyit.tradingsessionengine.clock;

import java.time.Instant;

/** Manually driven clock used in tests to replay a deterministic time sequence. */
public class MutableClock implements Clock {

    private Instant current;

    public MutableClock() {
        this.current = Instant.EPOCH;
    }

    public MutableClock(Instant initial) {
        this.current = initial;
    }

    public void setTime(Instant instant) {
        this.current = instant;
    }

    public void advanceMillis(long millis) {
        this.current = this.current.plusMillis(millis);
    }

    @Override
    public Instant now() {
        return current;
    }
}

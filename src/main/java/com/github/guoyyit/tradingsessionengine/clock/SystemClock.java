package com.github.guoyyit.tradingsessionengine.clock;

import java.time.Instant;

/** Clock backed by the system wall clock. */
public class SystemClock implements Clock {
    @Override
    public Instant now() {
        return Instant.now();
    }
}

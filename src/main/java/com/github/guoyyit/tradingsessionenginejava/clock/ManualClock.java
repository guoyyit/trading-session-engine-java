package com.github.guoyyit.tradingsessionenginejava.clock;

import java.time.Instant;

/**
 * 手动时钟：测试专用，时间只能显式推进，不依赖真实时间。
 */
public final class ManualClock implements TradingClock {

    private Instant now;

    public ManualClock(Instant start) {
        this.now = start;
    }

    @Override
    public synchronized Instant now() {
        return now;
    }

    public synchronized void set(Instant instant) {
        this.now = instant;
    }

    public synchronized void advanceMillis(long millis) {
        this.now = this.now.plusMillis(millis);
    }
}

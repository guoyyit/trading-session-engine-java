package com.github.guoyyit.tradingsessionenginejava.clock;

import java.time.Instant;

/**
 * 系统时钟：生产环境使用。
 */
public final class SystemClock implements TradingClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}

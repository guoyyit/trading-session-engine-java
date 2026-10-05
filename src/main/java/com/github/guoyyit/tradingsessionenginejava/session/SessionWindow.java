package com.github.guoyyit.tradingsessionenginejava.session;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个交易时段窗口：[start, end) 左闭右开。
 */
public record SessionWindow(SessionPhase phase, Instant start, Instant end) {

    public SessionWindow {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("window start must be before end");
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }
}

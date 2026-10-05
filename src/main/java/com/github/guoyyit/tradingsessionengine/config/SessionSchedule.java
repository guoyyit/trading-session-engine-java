package com.github.guoyyit.tradingsessionengine.config;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Ordered, non-overlapping segments making up one trading day. {@code zone}
 * pins how segment wall-clock times map to injected {@code Instant} values so
 * behavior is identical in every environment.
 */
public record SessionSchedule(LocalDate tradingDay,
                              List<SessionSegment> segments,
                              ZoneId zone) {

    public SessionSchedule(LocalDate tradingDay, List<SessionSegment> segments) {
        this(tradingDay, segments, ZoneId.systemDefault());
    }
}

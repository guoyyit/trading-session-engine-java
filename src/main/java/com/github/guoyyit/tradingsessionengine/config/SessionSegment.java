package com.github.guoyyit.tradingsessionengine.config;

import com.github.guoyyit.tradingsessionengine.model.MarketPhase;

import java.time.LocalTime;

/**
 * One contiguous segment of a trading day. The segment occupies
 * [{@code start}, {@code end}); phases advance at the exact boundary and a
 * request arriving at the boundary instant belongs to the next segment.
 */
public record SessionSegment(MarketPhase phase,
                             LocalTime start,
                             LocalTime end) {
}

package com.github.guoyyit.tradingsessionengine.model;

import java.time.Instant;
import java.util.List;

/** Immutable self-consistent view of one instrument's disposition state. */
public record InstrumentSnapshot(String instrumentId,
                                 InstrumentStatus status,
                                 HaltReason haltReason,
                                 Instant haltedAt,
                                 Instant resumeAt,
                                 PriceBand staticBand,
                                 PriceBand dynamicBand,
                                 int restingOrderCount,
                                 List<String> queuedOrderIds) {
}

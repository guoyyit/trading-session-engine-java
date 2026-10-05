package com.github.guoyyit.tradingsessionengine.model;

import java.math.BigDecimal;
import java.time.Instant;

/** A market-data tick that may move the dynamic price band. */
public record QuoteTick(String instrumentId,
                        BigDecimal lastPrice,
                        BigDecimal staticReferencePrice,
                        Instant timestamp) {
}

package com.github.guoyyit.tradingsessionengine.model;

import java.math.BigDecimal;

/**
 * An inclusive price band [lower, upper]. A band is immutable once published;
 * moving the dynamic band creates a new version ({@code version}) so that the
 * decision for any single order always references exactly one band version.
 */
public record PriceBand(PriceBandType type,
                        BigDecimal lower,
                        BigDecimal upper,
                        long version) {
}

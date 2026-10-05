package com.github.guoyyit.tradingsessionenginejava.pricelimit;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 价格带：[lower, upper] 闭区间。null 表示该方向无限制。
 */
public record PriceBand(BigDecimal lower, BigDecimal upper) {

    public static PriceBand unbounded() {
        return new PriceBand(null, null);
    }

    public boolean contains(BigDecimal price) {
        Objects.requireNonNull(price, "price");
        if (lower != null && price.compareTo(lower) < 0) {
            return false;
        }
        return upper == null || price.compareTo(upper) <= 0;
    }
}

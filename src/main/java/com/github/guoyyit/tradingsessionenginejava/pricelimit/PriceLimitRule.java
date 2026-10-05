package com.github.guoyyit.tradingsessionenginejava.pricelimit;

import java.math.BigDecimal;

/**
 * 价格限制规则：基于参考价计算价格带。
 * STATIC 参考价固定（如前收盘价），DYNAMIC 参考价随行情（最新成交价）移动。
 */
public record PriceLimitRule(Kind kind, BigDecimal percentLimit) {

    public enum Kind {
        STATIC,
        DYNAMIC
    }

    public PriceBand bandFor(BigDecimal referencePrice) {
        if (referencePrice == null || percentLimit == null) {
            return PriceBand.unbounded();
        }
        BigDecimal delta = referencePrice.multiply(percentLimit);
        return new PriceBand(referencePrice.subtract(delta), referencePrice.add(delta));
    }
}

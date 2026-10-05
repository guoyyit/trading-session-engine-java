package com.github.guoyyit.tradingsessionenginejava.order;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 订单请求。
 */
public record OrderRequest(
        String orderId,
        String instrumentId,
        Side side,
        BigDecimal price,
        long quantity) {

    public enum Side {
        BUY,
        SELL
    }

    public OrderRequest {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(price, "price");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}

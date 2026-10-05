package com.github.guoyyit.tradingsessionenginejava.order;

import java.util.Objects;

/**
 * 撤单请求。
 */
public record CancelRequest(String orderId, String instrumentId) {

    public CancelRequest {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(instrumentId, "instrumentId");
    }
}

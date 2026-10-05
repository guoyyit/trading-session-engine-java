package com.github.guoyyit.tradingsessionengine.model;

import java.math.BigDecimal;

/** An inbound new-order request judged against the current state. */
public record OrderRequest(String orderId,
                           String instrumentId,
                           BigDecimal price,
                           long quantity) {
}

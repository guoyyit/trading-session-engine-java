package com.github.guoyyit.tradingsessionengine.model;

/** An inbound cancel request; references the resting order by its id. */
public record CancelRequest(String orderId,
                            String instrumentId) {
}

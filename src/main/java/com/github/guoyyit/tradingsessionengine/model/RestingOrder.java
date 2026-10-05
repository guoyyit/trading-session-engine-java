package com.github.guoyyit.tradingsessionengine.model;

import java.time.Instant;

/**
 * An accepted order resting in the book. Records the phase in which it was
 * accepted so that resumes / phase re-entry can never mis-attribute it.
 */
public record RestingOrder(String orderId,
                           String instrumentId,
                           MarketPhase acceptedPhase,
                           long acceptedBandVersion,
                           Instant acceptedAt) {
}

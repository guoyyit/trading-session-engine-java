package com.github.guoyyit.tradingsessionengine.model;

/**
 * The engine's verdict for a single request. Deterministic and fully explained.
 */
public record Decision(OrderAction action,
                       RejectReason reason,
                       MarketPhase phaseAtDecision,
                       long staticBandVersion,
                       long dynamicBandVersion) {
}

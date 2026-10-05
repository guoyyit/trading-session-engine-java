package com.github.guoyyit.tradingsessionengine.audit;

import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.HaltReason;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;
import com.github.guoyyit.tradingsessionengine.model.PriceBand;
import com.github.guoyyit.tradingsessionengine.model.RejectReason;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JSON payloads carried by {@link AuditEvent}. Every field needed to rebuild
 * state by replay is present here; nothing is reconstructed from wall time.
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    public record CommandPayload(String command, String orderId, String instrumentId,
                                 BigDecimal price, long quantity,
                                 OrderAction action, RejectReason reason) {
    }

    public record SessionStartedPayload(String tradingDay) {
    }

    public record PhaseChangedPayload(MarketPhase from, MarketPhase to) {
    }

    public record InstrumentRegisteredPayload(String instrumentId,
                                              BigDecimal staticReferencePrice,
                                              boolean haltOnStaticViolation,
                                              boolean haltOnDynamicViolation) {
    }

    public record HaltedPayload(String instrumentId, HaltReason reason) {
    }

    public record ResumedPayload(String instrumentId) {
    }

    public record OrderPayload(String orderId, String instrumentId,
                               MarketPhase acceptedPhase, long acceptedBandVersion) {
    }

    public record OrderRejectedPayload(String orderId, String instrumentId, RejectReason reason) {
    }

    public record BandPayload(String instrumentId, PriceBand band) {
    }

    public record BreakerTriggeredPayload(BreakerLevel level,
                                          MarketPhase haltedFromPhase,
                                          Instant haltUntil,
                                          Instant cooldownUntil) {
    }

    public record BreakerStageEndedPayload(BreakerLevel level,
                                          MarketPhase nextPhase,
                                          Instant cooldownUntil) {
    }
}

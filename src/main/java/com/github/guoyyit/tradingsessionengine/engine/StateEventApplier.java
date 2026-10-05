package com.github.guoyyit.tradingsessionengine.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.tradingsessionengine.audit.AuditEvent;
import com.github.guoyyit.tradingsessionengine.audit.EventPayloads;
import com.github.guoyyit.tradingsessionengine.audit.EventType;
import com.github.guoyyit.tradingsessionengine.model.InstrumentStatus;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;
import com.github.guoyyit.tradingsessionengine.model.PriceBand;
import com.github.guoyyit.tradingsessionengine.model.RestingOrder;

/**
 * The single place that folds an event into mutable state. Used both by the
 * live engine (after a durable append) and by the crash-recovery replayer, so
 * online and rebuilt states can never diverge.
 */
public final class StateEventApplier {

    private final ObjectMapper mapper;

    public StateEventApplier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void apply(EngineState state, AuditEvent event) {
        try {
            switch (event.type()) {
                case SESSION_STARTED -> {
                    EventPayloads.SessionStartedPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.SessionStartedPayload.class);
                    state.tradingDay = payload.tradingDay();
                    state.started = true;
                    state.phase = MarketPhase.PRE_OPEN;
                    state.phaseSince = event.clock();
                }
                case PHASE_CHANGED -> {
                    EventPayloads.PhaseChangedPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.PhaseChangedPayload.class);
                    state.phase = payload.to();
                    state.phaseSince = event.clock();
                }
                case INSTRUMENT_REGISTERED -> {
                    EventPayloads.InstrumentRegisteredPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.InstrumentRegisteredPayload.class);
                    InstrumentState instrument =
                            state.instruments.computeIfAbsent(payload.instrumentId(), InstrumentState::new);
                    instrument.haltOnStaticViolation = payload.haltOnStaticViolation();
                    instrument.haltOnDynamicViolation = payload.haltOnDynamicViolation();
                }
                case INSTRUMENT_HALTED -> {
                    EventPayloads.HaltedPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.HaltedPayload.class);
                    InstrumentState instrument = state.instruments.get(payload.instrumentId());
                    instrument.status = InstrumentStatus.HALTED;
                    instrument.haltReason = payload.reason();
                    instrument.haltedAt = event.clock();
                }
                case INSTRUMENT_RESUMED -> {
                    EventPayloads.ResumedPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.ResumedPayload.class);
                    InstrumentState instrument = state.instruments.get(payload.instrumentId());
                    instrument.status = InstrumentStatus.ACTIVE;
                    instrument.haltReason = null;
                    instrument.haltedAt = null;
                    instrument.resumeAt = event.clock();
                    instrument.queuedOrderIds.clear();
                }
                case ORDER_ACCEPTED -> {
                    EventPayloads.OrderPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.OrderPayload.class);
                    InstrumentState instrument = state.instruments.get(payload.instrumentId());
                    instrument.restingOrders.add(new RestingOrder(payload.orderId(), payload.instrumentId(),
                            payload.acceptedPhase(), payload.acceptedBandVersion(), event.clock()));
                    instrument.queuedOrderIds.remove(payload.orderId());
                }
                case ORDER_REJECTED -> {
                    EventPayloads.OrderRejectedPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.OrderRejectedPayload.class);
                    InstrumentState instrument = state.instruments.get(payload.instrumentId());
                    instrument.queuedOrderIds.remove(payload.orderId());
                }
                case ORDER_QUEUED -> {
                    EventPayloads.OrderPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.OrderPayload.class);
                    InstrumentState instrument = state.instruments.get(payload.instrumentId());
                    if (!instrument.queuedOrderIds.contains(payload.orderId())) {
                        instrument.queuedOrderIds.add(payload.orderId());
                    }
                }
                case ORDER_CANCELLED -> {
                    EventPayloads.OrderPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.OrderPayload.class);
                    InstrumentState instrument = state.instruments.get(payload.instrumentId());
                    instrument.restingOrders.removeIf(order -> order.orderId().equals(payload.orderId()));
                }
                case CANCEL_REJECTED -> {
                    // Audit-only: no resting state changes.
                }
                case STATIC_BAND_SET, DYNAMIC_BAND_MOVED -> {
                    EventPayloads.BandPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.BandPayload.class);
                    InstrumentState instrument = state.instruments.get(payload.instrumentId());
                    PriceBand band = payload.band();
                    if (event.type() == EventType.STATIC_BAND_SET) {
                        instrument.staticBand = band;
                    } else {
                        instrument.dynamicBand = band;
                    }
                }
                case BREAKER_TRIGGERED -> {
                    EventPayloads.BreakerTriggeredPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.BreakerTriggeredPayload.class);
                    state.breakerActive = true;
                    state.breakerLevel = payload.level();
                    state.breakerTriggeredAt = event.clock();
                    state.breakerHaltUntil = payload.haltUntil();
                    state.breakerCooldownUntil = payload.cooldownUntil();
                    state.preBreakerPhase = payload.haltedFromPhase();
                    state.breakerHaltsToday++;
                    state.phase = MarketPhase.BREAKER_HALT;
                    state.phaseSince = event.clock();
                }
                case BREAKER_HALT_ENDED -> {
                    EventPayloads.BreakerStageEndedPayload haltEnded = mapper.readValue(event.payload(),
                            EventPayloads.BreakerStageEndedPayload.class);
                    state.phase = MarketPhase.BREAKER_COOLDOWN;
                    state.phaseSince = event.clock();
                    state.breakerCooldownStartedAt = event.clock();
                    state.breakerCooldownUntil = haltEnded.cooldownUntil();
                }
                case BREAKER_COOLDOWN_ENDED -> {
                    EventPayloads.BreakerStageEndedPayload payload = mapper.readValue(event.payload(),
                            EventPayloads.BreakerStageEndedPayload.class);
                    state.phase = payload.nextPhase();
                    state.phaseSince = event.clock();
                    state.breakerActive = false;
                    state.breakerLevel = null;
                    state.breakerTriggeredAt = null;
                    state.breakerHaltUntil = null;
                    state.breakerCooldownStartedAt = null;
                    state.breakerCooldownUntil = null;
                    state.preBreakerPhase = null;
                }
                case COMMAND -> {
                    // Pure audit record, never changes state.
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to apply event " + event.sequence(), e);
        }
    }
}

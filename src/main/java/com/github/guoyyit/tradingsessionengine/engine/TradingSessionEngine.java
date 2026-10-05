package com.github.guoyyit.tradingsessionengine.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.tradingsessionengine.audit.AuditEvent;
import com.github.guoyyit.tradingsessionengine.audit.EventPayloads;
import com.github.guoyyit.tradingsessionengine.audit.EventStore;
import com.github.guoyyit.tradingsessionengine.audit.EventType;
import com.github.guoyyit.tradingsessionengine.clock.Clock;
import com.github.guoyyit.tradingsessionengine.config.EngineConfig;
import com.github.guoyyit.tradingsessionengine.config.SessionSchedule;
import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.BreakerSnapshot;
import com.github.guoyyit.tradingsessionengine.model.CancelRequest;
import com.github.guoyyit.tradingsessionengine.model.Decision;
import com.github.guoyyit.tradingsessionengine.model.HaltPolicy;
import com.github.guoyyit.tradingsessionengine.model.HaltReason;
import com.github.guoyyit.tradingsessionengine.model.InstrumentSnapshot;
import com.github.guoyyit.tradingsessionengine.model.InstrumentStatus;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;
import com.github.guoyyit.tradingsessionengine.model.MarketSnapshot;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;
import com.github.guoyyit.tradingsessionengine.model.OrderRequest;
import com.github.guoyyit.tradingsessionengine.model.PriceBand;
import com.github.guoyyit.tradingsessionengine.model.PriceBandType;
import com.github.guoyyit.tradingsessionengine.model.QuoteTick;
import com.github.guoyyit.tradingsessionengine.model.RejectReason;
import com.github.guoyyit.tradingsessionengine.model.RestingOrder;
import com.github.guoyyit.tradingsessionengine.replay.Replayer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified facade for trading-session and per-instrument disposition decisions.
 *
 * <p>Concurrency: one intrinsic lock guards every method, so every public
 * operation is linearizable; a snapshot is always a self-consistent view at
 * one event sequence number, and a request straddling a phase switch is
 * attributed to exactly one phase (the phase observed inside the lock).
 *
 * <p>Durability: each operation computes a batch of events, appends them
 * atomically to the {@link EventStore}, then folds them into the in-memory
 * state via {@link StateEventApplier}. Crash recovery replays the same log.
 */
public class TradingSessionEngine {

    private final Clock clock;
    private final EngineConfig config;
    private final SessionSchedule schedule;
    private final EventStore eventStore;
    private final ObjectMapper mapper;
    private final StateEventApplier applier;
    private final SessionStateMachine sessionStateMachine;
    private final CircuitBreaker circuitBreaker;
    private final PriceLimitService priceLimits;
    private final InstrumentStateMachine instruments;

    private final EngineState state = new EngineState();
    private long staticBandVersion;
    private long dynamicBandVersion;

    public TradingSessionEngine(Clock clock,
                                EngineConfig config,
                                SessionSchedule schedule,
                                EventStore eventStore) {
        this.clock = clock;
        this.config = config;
        this.schedule = schedule;
        this.eventStore = eventStore;
        this.mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        this.applier = new StateEventApplier(mapper);
        this.sessionStateMachine = new SessionStateMachine(schedule);
        this.circuitBreaker = new CircuitBreaker(config.breakerConfig());
        this.priceLimits = new PriceLimitService(config);
        this.instruments = new InstrumentStateMachine(config.defaultHaltPolicy());
    }

    public synchronized void start() {
        recover();
        if (state.started) {
            return;
        }
        Instant now = clock.now();
        state.tradingDay = schedule.tradingDay().toString();
        commit(List.of(event(EventType.SESSION_STARTED,
                new EventPayloads.SessionStartedPayload(state.tradingDay))), now);
    }

    public synchronized void recover() {
        EngineState rebuilt = new Replayer(eventStore, mapper).rebuild();
        copyInto(rebuilt, state);
        long maxBandVersion = 0;
        for (InstrumentState instrument : state.instruments.values()) {
            if (instrument.staticBand != null) {
                maxBandVersion = Math.max(maxBandVersion, instrument.staticBand.version());
            }
            if (instrument.dynamicBand != null) {
                maxBandVersion = Math.max(maxBandVersion, instrument.dynamicBand.version());
            }
        }
        staticBandVersion = maxBandVersion;
        dynamicBandVersion = maxBandVersion;
    }

    public synchronized void registerInstrument(String instrumentId,
                                                BigDecimal staticReferencePrice,
                                                boolean haltOnStaticViolation,
                                                boolean haltOnDynamicViolation) {
        ensureStarted();
        if (state.instruments.containsKey(instrumentId)) {
            throw new IllegalStateException("instrument already registered: " + instrumentId);
        }
        Instant now = clock.now();
        long bandVersion = ++staticBandVersion;
        PriceBand band = priceLimits.buildBand(PriceBandType.STATIC, staticReferencePrice, bandVersion);
        commit(List.of(
                event(EventType.INSTRUMENT_REGISTERED, new EventPayloads.InstrumentRegisteredPayload(
                        instrumentId, staticReferencePrice, haltOnStaticViolation, haltOnDynamicViolation)),
                event(EventType.STATIC_BAND_SET, new EventPayloads.BandPayload(instrumentId, band))
        ), now);
    }

    public synchronized void setHaltPolicy(String instrumentId, HaltPolicy policy) {
        ensureInstrument(instrumentId);
        instruments.setPolicy(instrumentId, policy);
        state.instruments.get(instrumentId).policyOverride = policy;
    }

    /**
     * Advances all time-driven transitions to the injected clock instant.
     * Breaker halt/cooldown expiry is evaluated first; the schedule drives the
     * market only while no breaker is active.
     */
    public synchronized void tick() {
        ensureStarted();
        Instant now = clock.now();
        if (state.breakerActive) {
            tickBreaker(now);
            return;
        }
        if (state.phase == MarketPhase.CLOSED) {
            return;
        }
        MarketPhase scheduled = sessionStateMachine.phaseAt(now);
        if (scheduled != state.phase) {
            MarketPhase previous = state.phase;
            commit(List.of(event(EventType.PHASE_CHANGED,
                    new EventPayloads.PhaseChangedPayload(previous, scheduled))), now);
            if (scheduled == MarketPhase.CLOSED) {
                purgeOnClose(now);
            }
        }
    }

    private void tickBreaker(Instant now) {
        if (state.phase == MarketPhase.BREAKER_HALT && !now.isBefore(state.breakerHaltUntil)) {
            if (circuitBreaker.isTerminal(state.breakerLevel)) {
                endBreaker(MarketPhase.CLOSED, now);
                purgeOnClose(now);
            } else {
                commit(List.of(event(EventType.BREAKER_HALT_ENDED,
                        new EventPayloads.BreakerStageEndedPayload(state.breakerLevel,
                                MarketPhase.BREAKER_COOLDOWN, state.breakerCooldownUntil))), now);
            }
            return;
        }
        if (state.phase == MarketPhase.BREAKER_COOLDOWN
                && state.breakerCooldownUntil != null
                && !now.isBefore(state.breakerCooldownUntil)) {
            MarketPhase resumeTo = scheduledResumePhase(now);
            endBreaker(resumeTo, now);
            if (resumeTo == MarketPhase.CLOSED) {
                purgeOnClose(now);
            }
        }
    }

    private MarketPhase scheduledResumePhase(Instant now) {
        MarketPhase scheduled = sessionStateMachine.phaseAt(now);
        if (scheduled == MarketPhase.PRE_OPEN || scheduled == MarketPhase.OPEN_AUCTION) {
            return MarketPhase.OPEN_AUCTION;
        }
        if (scheduled == MarketPhase.CONTINUOUS || scheduled == MarketPhase.CLOSE_AUCTION) {
            return scheduled;
        }
        return MarketPhase.CLOSED;
    }

    private void endBreaker(MarketPhase nextPhase, Instant now) {
        commit(List.of(event(EventType.BREAKER_COOLDOWN_ENDED,
                new EventPayloads.BreakerStageEndedPayload(state.breakerLevel, nextPhase, null))), now);
    }

    public synchronized void halt(String instrumentId, HaltReason reason) {
        ensureStarted();
        InstrumentState instrument = ensureInstrument(instrumentId);
        if (instrument.status == InstrumentStatus.HALTED) {
            return;
        }
        Instant now = clock.now();
        List<AuditEvent> batch = new ArrayList<>();
        batch.add(event(EventType.INSTRUMENT_HALTED,
                new EventPayloads.HaltedPayload(instrumentId, reason)));
        OrderAction restingStrategy = instruments.restingStrategy(instrumentId);
        for (RestingOrder resting : new ArrayList<>(instrument.restingOrders)) {
            if (restingStrategy == OrderAction.REJECT) {
                batch.add(orderEvent(EventType.ORDER_CANCELLED, resting));
            }
        }
        commit(batch, now);
    }

    public synchronized void resume(String instrumentId) {
        ensureStarted();
        InstrumentState instrument = ensureInstrument(instrumentId);
        if (instrument.status == InstrumentStatus.ACTIVE) {
            return;
        }
        Instant now = clock.now();
        List<AuditEvent> batch = new ArrayList<>();
        batch.add(event(EventType.INSTRUMENT_RESUMED,
                new EventPayloads.ResumedPayload(instrumentId)));
        MarketPhase phase = state.phase;
        boolean acceptInto = isOrderAccepting(phase) && phase == MarketPhase.CONTINUOUS;
        for (String queuedId : new ArrayList<>(instrument.queuedOrderIds)) {
            if (acceptInto) {
                batch.add(event(EventType.ORDER_ACCEPTED, new EventPayloads.OrderPayload(
                        queuedId, instrumentId, phase, currentBandVersion(instrument))));
            } else {
                batch.add(event(EventType.ORDER_REJECTED, new EventPayloads.OrderRejectedPayload(
                        queuedId, instrumentId, RejectReason.PHASE_NOT_ORDER_ACCEPTING)));
            }
        }
        commit(batch, now);
    }

    /**
     * Triggers a graded market-wide circuit breaker. Recovery is never
     * immediate: non-terminal levels pass through BREAKER_HALT then
     * BREAKER_COOLDOWN; CLOSE ends trading for the day.
     */
    public synchronized void triggerBreaker(BreakerLevel level) {
        ensureStarted();
        Instant now = clock.now();
        if (state.breakerActive) {
            return;
        }
        if (state.phase == MarketPhase.CLOSED) {
            return;
        }
        Instant haltUntil = circuitBreaker.haltEnd(now, level);
        Instant cooldownUntil = circuitBreaker.cooldownEnd(haltUntil, level);
        if (circuitBreaker.isTerminal(level)) {
            commit(List.of(
                    event(EventType.BREAKER_TRIGGERED,
                            new EventPayloads.BreakerTriggeredPayload(level, state.phase, haltUntil, cooldownUntil)),
                    event(EventType.BREAKER_COOLDOWN_ENDED,
                            new EventPayloads.BreakerStageEndedPayload(level, MarketPhase.CLOSED, null))), now);
            purgeOnClose(now);
        } else {
            commit(List.of(event(EventType.BREAKER_TRIGGERED,
                    new EventPayloads.BreakerTriggeredPayload(level, state.phase, haltUntil, cooldownUntil))), now);
        }
    }

    public synchronized Decision onOrder(OrderRequest request) {
        ensureStarted();
        tick();
        Instant now = clock.now();
        MarketPhase phase = state.phase;
        InstrumentState instrument = state.instruments.get(request.instrumentId());

        RejectReason early = earlyReject(phase, instrument, request.orderId());
        if (early != RejectReason.NONE) {
            rejectOrder(request, early, now);
            return decision(OrderAction.REJECT, early, phase, instrument);
        }

        if (instrument.status == InstrumentStatus.HALTED) {
            OrderAction strategy = instruments.incomingOrderStrategy(request.instrumentId());
            return applyIncomingOrderStrategy(request, strategy, phase, instrument, now);
        }

        RejectReason bandReason = checkBands(instrument, request.price());
        if (bandReason != RejectReason.NONE) {
            if (instrument.haltOnStaticViolation && bandReason == RejectReason.PRICE_OUTSIDE_STATIC_BAND) {
                haltAndReject(request, bandReason, HaltReason.RULE_STATIC_PRICE, now);
                return decision(OrderAction.REJECT, bandReason, phase, instrument);
            }
            if (instrument.haltOnDynamicViolation && bandReason == RejectReason.PRICE_OUTSIDE_DYNAMIC_BAND) {
                haltAndReject(request, bandReason, HaltReason.RULE_DYNAMIC_PRICE, now);
                return decision(OrderAction.REJECT, bandReason, phase, instrument);
            }
            rejectOrder(request, bandReason, now);
            return decision(OrderAction.REJECT, bandReason, phase, instrument);
        }

        if (isOrderAccepting(phase)) {
            acceptOrder(request, phase, instrument, now);
            return decision(OrderAction.ACCEPT, RejectReason.NONE, phase, instrument);
        }

        rejectOrder(request, RejectReason.PHASE_NOT_ORDER_ACCEPTING, now);
        return decision(OrderAction.REJECT, RejectReason.PHASE_NOT_ORDER_ACCEPTING, phase, instrument);
    }

    public synchronized Decision onCancel(CancelRequest request) {
        ensureStarted();
        tick();
        Instant now = clock.now();
        MarketPhase phase = state.phase;
        InstrumentState instrument = state.instruments.get(request.instrumentId());
        if (instrument == null) {
            return decision(OrderAction.REJECT, RejectReason.UNKNOWN_INSTRUMENT, phase, null);
        }

        RestingOrder target = findResting(instrument, request.orderId());
        if (instrument.status == InstrumentStatus.HALTED) {
            OrderAction strategy = instruments.incomingCancelStrategy(request.instrumentId());
            if (strategy == OrderAction.ACCEPT && target != null) {
                commitCancelAccepted(target, now);
                return decision(OrderAction.ACCEPT, RejectReason.NONE, phase, instrument);
            }
            RejectReason reason = target == null && strategy == OrderAction.ACCEPT
                    ? RejectReason.ORDER_NOT_FOUND : RejectReason.INSTRUMENT_HALTED;
            auditCancelRejected(request, reason, now);
            return decision(OrderAction.REJECT, reason, phase, instrument);
        }

        if (state.breakerActive && phase == MarketPhase.BREAKER_HALT) {
            auditCancelRejected(request, RejectReason.MARKET_BREAKER_HALT, now);
            return decision(OrderAction.REJECT, RejectReason.MARKET_BREAKER_HALT, phase, instrument);
        }
        if (state.breakerActive && phase == MarketPhase.BREAKER_COOLDOWN) {
            auditCancelRejected(request, RejectReason.MARKET_BREAKER_COOLDOWN, now);
            return decision(OrderAction.REJECT, RejectReason.MARKET_BREAKER_COOLDOWN, phase, instrument);
        }
        if (target == null) {
            auditCancelRejected(request, RejectReason.ORDER_NOT_FOUND, now);
            return decision(OrderAction.REJECT, RejectReason.ORDER_NOT_FOUND, phase, instrument);
        }
        commitCancelAccepted(target, now);
        return decision(OrderAction.ACCEPT, RejectReason.NONE, phase, instrument);
    }

    private void commitCancelAccepted(RestingOrder target, Instant now) {
        commit(List.of(orderEvent(EventType.ORDER_CANCELLED, target),
                event(EventType.COMMAND, new EventPayloads.CommandPayload(
                        "CANCEL", target.orderId(), target.instrumentId(), null, 0,
                        OrderAction.ACCEPT, RejectReason.NONE))), now);
    }

    /**
     * Applies a quote tick and moves the dynamic band to a new version.
     * Resting orders retain the band version they were accepted against, so a
     * band move can never retroactively change an earlier verdict.
     */
    public synchronized void onQuote(QuoteTick tick) {
        ensureStarted();
        InstrumentState instrument = ensureInstrument(tick.instrumentId());
        Instant now = clock.now();
        List<AuditEvent> batch = new ArrayList<>();
        if (tick.staticReferencePrice() != null
                && (instrument.staticBand == null
                    || tick.staticReferencePrice().compareTo(staticReference(instrument)) != 0)) {
            long version = ++staticBandVersion;
            PriceBand band = priceLimits.buildBand(PriceBandType.STATIC, tick.staticReferencePrice(), version);
            batch.add(event(EventType.STATIC_BAND_SET, new EventPayloads.BandPayload(tick.instrumentId(), band)));
        }
        long dynamicVersion = ++dynamicBandVersion;
        PriceBand dynamic = priceLimits.buildBand(PriceBandType.DYNAMIC, tick.lastPrice(), dynamicVersion);
        batch.add(event(EventType.DYNAMIC_BAND_MOVED,
                new EventPayloads.BandPayload(tick.instrumentId(), dynamic)));
        commit(batch, now);
        instrument.lastTick = tick;
    }

    public synchronized MarketSnapshot snapshot() {
        List<InstrumentSnapshot> instrumentSnapshots = new ArrayList<>();
        for (InstrumentState instrument : state.instruments.values()) {
            instrumentSnapshots.add(new InstrumentSnapshot(
                    instrument.instrumentId,
                    instrument.status,
                    instrument.haltReason,
                    instrument.haltedAt,
                    instrument.resumeAt,
                    instrument.staticBand,
                    instrument.dynamicBand,
                    instrument.restingOrders.size(),
                    List.copyOf(instrument.queuedOrderIds)));
        }
        BreakerSnapshot breaker = new BreakerSnapshot(
                state.breakerActive,
                state.breakerLevel,
                state.phase,
                state.breakerTriggeredAt,
                state.breakerHaltUntil,
                state.breakerCooldownUntil,
                state.breakerHaltsToday);
        return new MarketSnapshot(state.tradingDay, state.phase, state.phaseSince,
                breaker, List.copyOf(instrumentSnapshots), eventStore.lastSequence());
    }

    // ------------------------------------------------------------------ helpers

    private void ensureStarted() {
        if (!state.started) {
            throw new IllegalStateException("trading day not started; call start() first");
        }
    }

    private InstrumentState ensureInstrument(String instrumentId) {
        InstrumentState instrument = state.instruments.get(instrumentId);
        if (instrument == null) {
            throw new IllegalArgumentException("unknown instrument: " + instrumentId);
        }
        return instrument;
    }

    private RejectReason earlyReject(MarketPhase phase, InstrumentState instrument, String orderId) {
        if (instrument == null) {
            return RejectReason.UNKNOWN_INSTRUMENT;
        }
        if (state.breakerActive && phase == MarketPhase.BREAKER_HALT) {
            return RejectReason.MARKET_BREAKER_HALT;
        }
        if (state.breakerActive && phase == MarketPhase.BREAKER_COOLDOWN) {
            return RejectReason.MARKET_BREAKER_COOLDOWN;
        }
        if (phase == MarketPhase.CLOSED || phase == MarketPhase.PRE_OPEN) {
            return RejectReason.MARKET_CLOSED;
        }
        if (findResting(instrument, orderId) != null || instrument.queuedOrderIds.contains(orderId)) {
            return RejectReason.DUPLICATE_ORDER_ID;
        }
        return RejectReason.NONE;
    }

    private RejectReason checkBands(InstrumentState instrument, BigDecimal price) {
        if (price == null) {
            return RejectReason.PRICE_OUTSIDE_STATIC_BAND;
        }
        if (instrument.staticBand != null && !priceLimits.isWithin(instrument.staticBand, price)) {
            return RejectReason.PRICE_OUTSIDE_STATIC_BAND;
        }
        if (instrument.dynamicBand != null && !priceLimits.isWithin(instrument.dynamicBand, price)) {
            return RejectReason.PRICE_OUTSIDE_DYNAMIC_BAND;
        }
        return RejectReason.NONE;
    }

    private Decision applyIncomingOrderStrategy(OrderRequest request, OrderAction strategy,
                                                MarketPhase phase, InstrumentState instrument,
                                                Instant now) {
        if (strategy == OrderAction.QUEUE && isOrderAccepting(phase)) {
            EventPayloads.OrderPayload queued = new EventPayloads.OrderPayload(
                    request.orderId(), request.instrumentId(), phase, currentBandVersion(instrument));
            commit(List.of(event(EventType.ORDER_QUEUED, queued),
                    event(EventType.COMMAND, new EventPayloads.CommandPayload(
                            "ORDER", request.orderId(), request.instrumentId(), request.price(),
                            request.quantity(), OrderAction.QUEUE, RejectReason.NONE))), now);
            return decision(OrderAction.QUEUE, RejectReason.NONE, phase, instrument);
        }
        if (strategy == OrderAction.QUEUE) {
            rejectOrder(request, RejectReason.PHASE_NOT_ORDER_ACCEPTING, now);
            return decision(OrderAction.REJECT, RejectReason.PHASE_NOT_ORDER_ACCEPTING, phase, instrument);
        }
        rejectOrder(request, RejectReason.INSTRUMENT_HALTED, now);
        return decision(OrderAction.REJECT, RejectReason.INSTRUMENT_HALTED, phase, instrument);
    }

    private void acceptOrder(OrderRequest request, MarketPhase phase,
                             InstrumentState instrument, Instant now) {
        EventPayloads.OrderPayload payload = new EventPayloads.OrderPayload(
                request.orderId(), request.instrumentId(), phase, currentBandVersion(instrument));
        EventPayloads.CommandPayload command = new EventPayloads.CommandPayload(
                "ORDER", request.orderId(), request.instrumentId(), request.price(),
                request.quantity(), OrderAction.ACCEPT, RejectReason.NONE);
        commit(List.of(event(EventType.ORDER_ACCEPTED, payload),
                event(EventType.COMMAND, command)), now);
    }

    private void rejectOrder(OrderRequest request, RejectReason reason, Instant now) {
        EventPayloads.CommandPayload command = new EventPayloads.CommandPayload(
                "ORDER", request.orderId(), request.instrumentId(), request.price(),
                request.quantity(), OrderAction.REJECT, reason);
        commit(List.of(event(EventType.ORDER_REJECTED, new EventPayloads.OrderRejectedPayload(
                request.orderId(), request.instrumentId(), reason)),
                event(EventType.COMMAND, command)), now);
    }

    private void haltAndReject(OrderRequest request, RejectReason bandReason,
                               HaltReason haltReason, Instant now) {
        List<AuditEvent> batch = new ArrayList<>();
        batch.add(event(EventType.INSTRUMENT_HALTED,
                new EventPayloads.HaltedPayload(request.instrumentId(), haltReason)));
        batch.add(event(EventType.ORDER_REJECTED, new EventPayloads.OrderRejectedPayload(
                request.orderId(), request.instrumentId(), bandReason)));
        commit(batch, now);
    }

    private void auditCancelRejected(CancelRequest request, RejectReason reason, Instant now) {
        EventPayloads.CommandPayload command = new EventPayloads.CommandPayload(
                "CANCEL", request.orderId(), request.instrumentId(), null, 0,
                OrderAction.REJECT, reason);
        commit(List.of(event(EventType.CANCEL_REJECTED,
                new EventPayloads.OrderRejectedPayload(request.orderId(), request.instrumentId(), reason)),
                event(EventType.COMMAND, command)), now);
    }

    private void purgeOnClose(Instant now) {
        List<AuditEvent> batch = new ArrayList<>();
        for (InstrumentState instrument : state.instruments.values()) {
            for (RestingOrder resting : new ArrayList<>(instrument.restingOrders)) {
                batch.add(orderEvent(EventType.ORDER_CANCELLED, resting));
            }
            for (String queuedId : new ArrayList<>(instrument.queuedOrderIds)) {
                batch.add(event(EventType.ORDER_REJECTED, new EventPayloads.OrderRejectedPayload(
                        queuedId, instrument.instrumentId, RejectReason.MARKET_CLOSED)));
            }
        }
        if (!batch.isEmpty()) {
            commit(batch, now);
        }
    }

    private boolean isOrderAccepting(MarketPhase phase) {
        return phase == MarketPhase.OPEN_AUCTION
                || phase == MarketPhase.CONTINUOUS
                || phase == MarketPhase.CLOSE_AUCTION;
    }

    private RestingOrder findResting(InstrumentState instrument, String orderId) {
        for (RestingOrder order : instrument.restingOrders) {
            if (order.orderId().equals(orderId)) {
                return order;
            }
        }
        return null;
    }

    private long currentBandVersion(InstrumentState instrument) {
        long version = instrument.staticBand == null ? 0 : instrument.staticBand.version();
        if (instrument.dynamicBand != null) {
            version = Math.max(version, instrument.dynamicBand.version());
        }
        return version;
    }

    private BigDecimal staticReference(InstrumentState instrument) {
        if (instrument.staticBand == null) {
            return null;
        }
        return instrument.staticBand.lower().add(instrument.staticBand.upper())
                .divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
    }

    private Decision decision(OrderAction action, RejectReason reason,
                              MarketPhase phase, InstrumentState instrument) {
        long staticVersion = instrument == null || instrument.staticBand == null
                ? 0 : instrument.staticBand.version();
        long dynamicVersion = instrument == null || instrument.dynamicBand == null
                ? 0 : instrument.dynamicBand.version();
        return new Decision(action, reason, phase, staticVersion, dynamicVersion);
    }

    private AuditEvent orderEvent(EventType type, RestingOrder order) {
        return event(type, new EventPayloads.OrderPayload(order.orderId(), order.instrumentId(),
                order.acceptedPhase(), order.acceptedBandVersion()));
    }

    private AuditEvent event(EventType type, Object payload) {
        try {
            long sequence = eventStore.lastSequence() + 1;
            return new AuditEvent(sequence, type, clock.now(), mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize event " + type, e);
        }
    }

    /**
     * Durable-first commit: the whole batch is appended atomically before any
     * in-memory change. A crash therefore loses either the entire operation
     * or none of it; the transition can never be half-applied.
     */
    private void commit(List<AuditEvent> batch, Instant at) {
        List<AuditEvent> stamped = new ArrayList<>(batch.size());
        long sequence = eventStore.lastSequence();
        for (AuditEvent candidate : batch) {
            sequence++;
            stamped.add(new AuditEvent(sequence, candidate.type(), at, candidate.payload()));
        }
        eventStore.appendBatch(stamped);
        eventStore.flush();
        for (AuditEvent stored : stamped) {
            applier.apply(state, stored);
        }
    }

    @SuppressWarnings("unchecked")
    private void copyInto(EngineState source, EngineState target) {
        target.tradingDay = source.tradingDay;
        target.phase = source.phase;
        target.phaseSince = source.phaseSince;
        target.preBreakerPhase = source.preBreakerPhase;
        target.breakerActive = source.breakerActive;
        target.breakerLevel = source.breakerLevel;
        target.breakerTriggeredAt = source.breakerTriggeredAt;
        target.breakerHaltUntil = source.breakerHaltUntil;
        target.breakerCooldownUntil = source.breakerCooldownUntil;
        target.breakerHaltsToday = source.breakerHaltsToday;
        target.started = source.started;
        target.instruments.clear();
        target.instruments.putAll(source.instruments);
    }
}

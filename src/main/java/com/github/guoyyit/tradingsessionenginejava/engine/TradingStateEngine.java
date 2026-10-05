package com.github.guoyyit.tradingsessionenginejava.engine;

import com.github.guoyyit.tradingsessionenginejava.audit.AuditEventType;
import com.github.guoyyit.tradingsessionenginejava.audit.AuditLog;
import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerManager;
import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerPhase;
import com.github.guoyyit.tradingsessionenginejava.clock.TradingClock;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltTrigger;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltedCancelPolicy;
import com.github.guoyyit.tradingsessionenginejava.instrument.HaltedOrderPolicy;
import com.github.guoyyit.tradingsessionenginejava.instrument.InstrumentStateManager;
import com.github.guoyyit.tradingsessionenginejava.instrument.InstrumentTradingState;
import com.github.guoyyit.tradingsessionenginejava.instrument.RestingOrderAction;
import com.github.guoyyit.tradingsessionenginejava.order.CancelRequest;
import com.github.guoyyit.tradingsessionenginejava.order.DecisionRecord;
import com.github.guoyyit.tradingsessionenginejava.order.OrderDecision;
import com.github.guoyyit.tradingsessionenginejava.order.OrderRequest;
import com.github.guoyyit.tradingsessionenginejava.pricelimit.PriceBand;
import com.github.guoyyit.tradingsessionenginejava.pricelimit.PriceLimitEngine;
import com.github.guoyyit.tradingsessionenginejava.session.DaySchedule;
import com.github.guoyyit.tradingsessionenginejava.session.SessionPhase;
import com.github.guoyyit.tradingsessionenginejava.session.SessionStateMachine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 交易状态引擎统一入口：协调时段、品种处置、价格限制与熔断，
 * 所有判定由注入时钟驱动，全部变化写入审计。
 *
 * 并发模型：所有状态迁移与请求判定持有写锁，快照读取持有读锁，
 * 单次读取得到自洽快照；阶段切换瞬间到达的请求由写锁串行化，
 * 归属由判定时刻的状态唯一确定。
 */
public final class TradingStateEngine {

    private final TradingClock clock;
    private final EngineConfig config;
    private final SessionStateMachine sessionMachine;
    private final InstrumentStateManager instrumentManager;
    private final PriceLimitEngine priceLimitEngine;
    private final CircuitBreakerManager circuitBreakerManager;
    private final AuditLog auditLog;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 已接受且仍有效的订单（orderId -> request） */
    private final Map<String, OrderRequest> restingOrders = new LinkedHashMap<>();
    /** 停牌期间按 QUEUE 策略排队的订单（instrumentId -> orderIds，保持到达顺序） */
    private final Map<String, List<String>> queuedOrders = new LinkedHashMap<>();

    public TradingStateEngine(
            TradingClock clock,
            EngineConfig config,
            DaySchedule schedule,
            AuditLog auditLog,
            List<com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerLevel> circuitBreakerLevels) {
        this.clock = clock;
        this.config = config;
        this.auditLog = auditLog;
        this.sessionMachine = new SessionStateMachine(clock, schedule);
        this.instrumentManager = new InstrumentStateManager(config.haltPolicies());
        this.priceLimitEngine = new PriceLimitEngine();
        this.circuitBreakerManager = new CircuitBreakerManager(clock, circuitBreakerLevels);
    }

    /**
     * 时钟推进：驱动时段与熔断状态迁移。相同的时钟序列产生逐次一致的迁移。
     */
    public void tick() {
        lock.writeLock().lock();
        try {
            advanceLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void advanceLocked() {
        if (sessionMachine.advance()) {
            auditLog.append(AuditEventType.SESSION_PHASE_CHANGED, Map.of(
                    "phase", sessionMachine.currentPhase().name(),
                    "enteredAt", sessionMachine.phaseEnteredAt().toString()));
        }
        if (circuitBreakerManager.advance()) {
            auditLog.append(AuditEventType.CIRCUIT_BREAKER_PHASE_CHANGED, Map.of(
                    "phase", circuitBreakerManager.currentPhase().name(),
                    "level", String.valueOf(circuitBreakerManager.currentLevel())));
        }
    }

    public DecisionRecord submitOrder(OrderRequest request) {
        lock.writeLock().lock();
        try {
            advanceLocked();
            Instant now = clock.now();
            SessionPhase phase = sessionMachine.currentPhase();
            DecisionRecord record = decideOrder(request, now, phase);
            if (record.decision() == OrderDecision.ACCEPTED) {
                restingOrders.put(request.orderId(), request);
            } else if (record.decision() == OrderDecision.QUEUED) {
                queuedOrders.computeIfAbsent(request.instrumentId(), k -> new ArrayList<>())
                        .add(request.orderId());
                restingOrders.put(request.orderId(), request);
            }
            auditLog.append(AuditEventType.ORDER_DECIDED, Map.of(
                    "orderId", request.orderId(),
                    "instrumentId", request.instrumentId(),
                    "decision", record.decision().name(),
                    "phase", phase.name(),
                    "reason", record.reason()));
            return record;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private DecisionRecord decideOrder(OrderRequest request, Instant now, SessionPhase phase) {
        CircuitBreakerPhase cbPhase = circuitBreakerManager.currentPhase();
        if (cbPhase == CircuitBreakerPhase.COOLING) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "circuit-breaker-cooling");
        }
        if (cbPhase == CircuitBreakerPhase.RESUMING) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "circuit-breaker-resuming");
        }
        if (phase == SessionPhase.CLOSED || phase == SessionPhase.BREAK) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "session-" + phase.name().toLowerCase());
        }
        boolean auction = phase == SessionPhase.OPENING_AUCTION || phase == SessionPhase.CLOSING_AUCTION;
        if (auction && !config.orderAllowedInAuction()) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "auction-orders-disabled");
        }
        if (instrumentManager.stateOf(request.instrumentId()) == InstrumentTradingState.HALTED) {
            if (config.haltPolicies().haltedOrderPolicy() == HaltedOrderPolicy.QUEUE) {
                return DecisionRecord.of(OrderDecision.QUEUED, now, phase, "instrument-halted-queued");
            }
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "instrument-halted");
        }
        if (!priceLimitEngine.isAllowed(request.instrumentId(), request.price())) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "price-out-of-band");
        }
        return DecisionRecord.of(OrderDecision.ACCEPTED, now, phase, "ok");
    }

    public DecisionRecord submitCancel(CancelRequest request) {
        lock.writeLock().lock();
        try {
            advanceLocked();
            Instant now = clock.now();
            SessionPhase phase = sessionMachine.currentPhase();
            DecisionRecord record = decideCancel(request, now, phase);
            if (record.decision() == OrderDecision.ACCEPTED) {
                restingOrders.remove(request.orderId());
                List<String> queued = queuedOrders.get(request.instrumentId());
                if (queued != null) {
                    queued.remove(request.orderId());
                }
            }
            auditLog.append(AuditEventType.CANCEL_DECIDED, Map.of(
                    "orderId", request.orderId(),
                    "instrumentId", request.instrumentId(),
                    "decision", record.decision().name(),
                    "phase", phase.name(),
                    "reason", record.reason()));
            return record;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private DecisionRecord decideCancel(CancelRequest request, Instant now, SessionPhase phase) {
        if (!restingOrders.containsKey(request.orderId())) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "unknown-order");
        }
        CircuitBreakerPhase cbPhase = circuitBreakerManager.currentPhase();
        if (cbPhase == CircuitBreakerPhase.COOLING) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "circuit-breaker-cooling");
        }
        boolean auction = phase == SessionPhase.OPENING_AUCTION || phase == SessionPhase.CLOSING_AUCTION;
        if (auction && !config.cancelAllowedInAuction()) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "auction-cancels-disabled");
        }
        if (instrumentManager.stateOf(request.instrumentId()) == InstrumentTradingState.HALTED
                && config.haltPolicies().haltedCancelPolicy() == HaltedCancelPolicy.REJECT) {
            return DecisionRecord.of(OrderDecision.REJECTED, now, phase, "instrument-halted");
        }
        return DecisionRecord.of(OrderDecision.ACCEPTED, now, phase, "ok");
    }

    public void haltInstrument(String instrumentId, HaltTrigger trigger) {
        lock.writeLock().lock();
        try {
            advanceLocked();
            if (instrumentManager.stateOf(instrumentId) == InstrumentTradingState.HALTED) {
                return;
            }
            instrumentManager.halt(instrumentId, trigger, clock.now());
            auditLog.append(AuditEventType.INSTRUMENT_HALTED, Map.of(
                    "instrumentId", instrumentId,
                    "trigger", trigger.name()));
            handleRestingOrdersLocked(instrumentId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void handleRestingOrdersLocked(String instrumentId) {
        RestingOrderAction action = config.haltPolicies().restingOrderAction();
        List<String> affected = new ArrayList<>();
        for (Map.Entry<String, OrderRequest> entry : restingOrders.entrySet()) {
            OrderRequest order = entry.getValue();
            boolean queued = queuedOrders.getOrDefault(instrumentId, List.of()).contains(order.orderId());
            if (order.instrumentId().equals(instrumentId) && !queued) {
                affected.add(order.orderId());
            }
        }
        if (action == RestingOrderAction.CANCEL_ALL) {
            affected.forEach(restingOrders::remove);
        }
        auditLog.append(AuditEventType.RESTING_ORDERS_HANDLED, Map.of(
                "instrumentId", instrumentId,
                "action", action.name(),
                "orderIds", String.join(";", affected)));
    }

    public void resumeInstrument(String instrumentId) {
        lock.writeLock().lock();
        try {
            advanceLocked();
            if (instrumentManager.stateOf(instrumentId) != InstrumentTradingState.HALTED) {
                return;
            }
            instrumentManager.resume(instrumentId, clock.now());
            auditLog.append(AuditEventType.INSTRUMENT_RESUMED, Map.of(
                    "instrumentId", instrumentId));
            List<String> queued = queuedOrders.remove(instrumentId);
            if (queued != null && !queued.isEmpty()) {
                SessionPhase phase = sessionMachine.currentPhase();
                for (String orderId : queued) {
                    auditLog.append(AuditEventType.ORDER_DECIDED, Map.of(
                            "orderId", orderId,
                            "instrumentId", instrumentId,
                            "decision", OrderDecision.ACCEPTED.name(),
                            "phase", phase.name(),
                            "reason", "resumed-from-queue"));
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setPriceLimitRule(String instrumentId,
                                  com.github.guoyyit.tradingsessionenginejava.pricelimit.PriceLimitRule rule) {
        lock.writeLock().lock();
        try {
            priceLimitEngine.setRule(instrumentId, rule);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setStaticReferencePrice(String instrumentId, BigDecimal price) {
        lock.writeLock().lock();
        try {
            priceLimitEngine.setStaticReference(instrumentId, price, clock.now());
            emitPriceBandChangedLocked(instrumentId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onTradePrice(String instrumentId, BigDecimal price) {
        lock.writeLock().lock();
        try {
            PriceBand before = priceLimitEngine.currentBand(instrumentId).orElse(null);
            priceLimitEngine.onTradePrice(instrumentId, price, clock.now());
            PriceBand after = priceLimitEngine.currentBand(instrumentId).orElse(null);
            if (!java.util.Objects.equals(before, after)) {
                emitPriceBandChangedLocked(instrumentId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void emitPriceBandChangedLocked(String instrumentId) {
        priceLimitEngine.currentBand(instrumentId).ifPresent(band ->
                auditLog.append(AuditEventType.PRICE_BAND_CHANGED, Map.of(
                        "instrumentId", instrumentId,
                        "lower", band.lower() == null ? "" : band.lower().toPlainString(),
                        "upper", band.upper() == null ? "" : band.upper().toPlainString())));
    }

    public void setMarketReference(BigDecimal reference) {
        lock.writeLock().lock();
        try {
            circuitBreakerManager.setMarketReference(reference);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onMarketIndex(BigDecimal indexValue) {
        lock.writeLock().lock();
        try {
            int levelBefore = circuitBreakerManager.currentLevel();
            circuitBreakerManager.onMarketIndex(indexValue, clock.now());
            if (circuitBreakerManager.currentLevel() > levelBefore) {
                auditLog.append(AuditEventType.CIRCUIT_BREAKER_TRIGGERED, Map.of(
                        "level", String.valueOf(circuitBreakerManager.currentLevel()),
                        "coolingEndsAt", circuitBreakerManager.coolingEndsAt()
                                .map(Instant::toString).orElse("")));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void confirmCircuitBreakerResume() {
        lock.writeLock().lock();
        try {
            if (circuitBreakerManager.currentPhase() == CircuitBreakerPhase.RESUMING) {
                circuitBreakerManager.confirmResume();
                auditLog.append(AuditEventType.CIRCUIT_BREAKER_PHASE_CHANGED, Map.of(
                        "phase", CircuitBreakerPhase.NONE.name(),
                        "level", "0"));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 读取自洽快照：与并发迁移互斥，单次读取内部一致。
     */
    public EngineSnapshot snapshot() {
        lock.readLock().lock();
        try {
            return new EngineSnapshot(
                    clock.now(),
                    sessionMachine.currentPhase(),
                    circuitBreakerManager.currentPhase(),
                    circuitBreakerManager.currentLevel(),
                    instrumentManager.exportStates(),
                    priceLimitEngine.exportBands(),
                    auditLog.lastSequence());
        } finally {
            lock.readLock().unlock();
        }
    }

    public EngineConfig config() {
        return config;
    }

    public SessionStateMachine sessionMachine() {
        return sessionMachine;
    }

    public InstrumentStateManager instrumentManager() {
        return instrumentManager;
    }

    public PriceLimitEngine priceLimitEngine() {
        return priceLimitEngine;
    }

    public CircuitBreakerManager circuitBreakerManager() {
        return circuitBreakerManager;
    }
}

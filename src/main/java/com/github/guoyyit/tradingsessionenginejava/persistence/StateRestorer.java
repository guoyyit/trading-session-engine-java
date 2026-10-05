package com.github.guoyyit.tradingsessionenginejava.persistence;

import com.github.guoyyit.tradingsessionenginejava.audit.AuditEvent;
import com.github.guoyyit.tradingsessionenginejava.audit.AuditLog;
import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerPhase;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineSnapshot;
import com.github.guoyyit.tradingsessionenginejava.instrument.InstrumentTradingState;
import com.github.guoyyit.tradingsessionenginejava.pricelimit.PriceBand;
import com.github.guoyyit.tradingsessionenginejava.session.SessionPhase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 崩溃恢复：从审计日志重放，重建崩溃前的完整状态。
 * 未完成的迁移（未落审计）不会半途生效。
 */
public final class StateRestorer {

    private final AuditLog auditLog;

    public StateRestorer(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * 重放全部审计事件，返回重建出的最新状态。
     */
    public Optional<EngineSnapshot> restore() {
        List<AuditEvent> events = auditLog.readAll();
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(replay(events));
    }

    /**
     * 重放到指定序号（含），用于重建任一时刻的状态。
     */
    public EngineSnapshot replayTo(long sequence) {
        List<AuditEvent> events = auditLog.readAll().stream()
                .filter(event -> event.sequence() <= sequence)
                .toList();
        return replay(events);
    }

    /**
     * 对任意事件流做纯重放（不依赖在线状态）。
     */
    public EngineSnapshot replay(List<AuditEvent> events) {
        SessionPhase sessionPhase = SessionPhase.CLOSED;
        CircuitBreakerPhase cbPhase = CircuitBreakerPhase.NONE;
        int cbLevel = 0;
        Map<String, InstrumentTradingState> instrumentStates = new HashMap<>();
        Map<String, PriceBand> priceBands = new HashMap<>();
        Instant at = Instant.EPOCH;
        long lastSequence = 0;

        for (AuditEvent event : events) {
            at = event.occurredAt();
            lastSequence = event.sequence();
            Map<String, String> payload = event.payload();
            switch (event.type()) {
                case SESSION_PHASE_CHANGED -> sessionPhase = SessionPhase.valueOf(payload.get("phase"));
                case INSTRUMENT_HALTED -> instrumentStates.put(
                        payload.get("instrumentId"), InstrumentTradingState.HALTED);
                case INSTRUMENT_RESUMED -> instrumentStates.remove(payload.get("instrumentId"));
                case PRICE_BAND_CHANGED -> priceBands.put(
                        payload.get("instrumentId"),
                        new PriceBand(
                                parseDecimal(payload.get("lower")),
                                parseDecimal(payload.get("upper"))));
                case CIRCUIT_BREAKER_TRIGGERED -> {
                    cbPhase = CircuitBreakerPhase.COOLING;
                    cbLevel = Integer.parseInt(payload.get("level"));
                }
                case CIRCUIT_BREAKER_PHASE_CHANGED -> {
                    cbPhase = CircuitBreakerPhase.valueOf(payload.get("phase"));
                    cbLevel = Integer.parseInt(payload.get("level"));
                }
                default -> {
                    // ORDER_DECIDED / CANCEL_DECIDED / RESTING_ORDERS_HANDLED / SNAPSHOT
                    // 不改变快照中的状态字段
                }
            }
        }
        return new EngineSnapshot(at, sessionPhase, cbPhase, cbLevel,
                instrumentStates, priceBands, lastSequence);
    }

    private static BigDecimal parseDecimal(String value) {
        return value == null || value.isEmpty() ? null : new BigDecimal(value);
    }
}

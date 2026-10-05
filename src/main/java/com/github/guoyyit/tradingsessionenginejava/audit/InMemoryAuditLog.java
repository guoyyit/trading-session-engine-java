package com.github.guoyyit.tradingsessionenginejava.audit;

import com.github.guoyyit.tradingsessionenginejava.clock.TradingClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内存审计日志（测试用）。
 */
public final class InMemoryAuditLog implements AuditLog {

    private final TradingClock clock;
    private final List<AuditEvent> events = new ArrayList<>();
    private long lastSequence;

    public InMemoryAuditLog(TradingClock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized long append(AuditEventType type, Map<String, String> payload) {
        lastSequence++;
        events.add(new AuditEvent(lastSequence, clock.now(), type, payload));
        return lastSequence;
    }

    @Override
    public synchronized List<AuditEvent> readAll() {
        return List.copyOf(events);
    }

    @Override
    public synchronized long lastSequence() {
        return lastSequence;
    }
}

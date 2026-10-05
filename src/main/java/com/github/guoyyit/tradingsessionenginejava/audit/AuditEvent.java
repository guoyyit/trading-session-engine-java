package com.github.guoyyit.tradingsessionenginejava.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一条不可变审计记录。payload 承载事件细节，足以重放重建状态。
 */
public record AuditEvent(
        long sequence,
        Instant occurredAt,
        AuditEventType type,
        Map<String, String> payload) {

    public AuditEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(type, "type");
        payload = Map.copyOf(payload);
    }
}

package com.github.guoyyit.tradingsessionengine.audit;

import java.time.Instant;

/**
 * One immutable, append-only audit record.
 *
 * <p>{@code sequence} is the global monotonically increasing position in the
 * log; {@code clock} is the injected-clock timestamp; {@code payload} is a
 * JSON document carrying all data needed to replay the transition.
 */
public record AuditEvent(long sequence,
                         EventType type,
                         Instant clock,
                         String payload) {
}

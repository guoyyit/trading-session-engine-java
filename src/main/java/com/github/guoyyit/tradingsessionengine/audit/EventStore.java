package com.github.guoyyit.tradingsessionengine.audit;

import java.util.List;

/**
 * Durable, ordered, append-only event log.
 *
 * <p>A batch is appended atomically: either every event in it is durable or
 * none is, so an uncompleted multi-event transition can never be half-applied
 * after a crash.
 */
public interface EventStore {

    void appendBatch(List<AuditEvent> batch);

    List<AuditEvent> readAll();

    long lastSequence();

    /** Forces buffered events to durable storage. */
    void flush();
}

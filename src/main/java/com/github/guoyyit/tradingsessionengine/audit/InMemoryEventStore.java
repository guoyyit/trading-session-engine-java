package com.github.guoyyit.tradingsessionengine.audit;

import java.util.ArrayList;
import java.util.List;

/** Event store backed by a list. Intended for tests and ephemeral runs. */
public class InMemoryEventStore implements EventStore {

    private final List<AuditEvent> events = new ArrayList<>();
    private long lastSequence;

    public InMemoryEventStore() {
    }

    @Override
    public synchronized void appendBatch(List<AuditEvent> batch) {
        for (AuditEvent event : batch) {
            if (event.sequence() != lastSequence + 1) {
                throw new IllegalStateException("non-contiguous event sequence: expected "
                        + (lastSequence + 1) + " got " + event.sequence());
            }
            events.add(event);
            lastSequence = event.sequence();
        }
    }

    @Override
    public synchronized List<AuditEvent> readAll() {
        return new ArrayList<>(events);
    }

    @Override
    public synchronized long lastSequence() {
        return lastSequence;
    }

    @Override
    public synchronized void flush() {
    }
}

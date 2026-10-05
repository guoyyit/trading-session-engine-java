package com.github.guoyyit.tradingsessionengine.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.tradingsessionengine.audit.AuditEvent;
import com.github.guoyyit.tradingsessionengine.audit.EventStore;
import com.github.guoyyit.tradingsessionengine.engine.EngineState;
import com.github.guoyyit.tradingsessionengine.engine.StateEventApplier;

/**
 * Rebuilds {@link EngineState} by folding the durable event log. COMMAND
 * events are skipped; every state-changing event is applied with the same
 * {@link StateEventApplier} the live engine uses, so rebuilt and online
 * states are identical at the same sequence number.
 */
public class Replayer {

    private final EventStore store;
    private final StateEventApplier applier;

    public Replayer(EventStore store) {
        this(store, JsonMapper.builder().addModule(new JavaTimeModule()).build());
    }

    public Replayer(EventStore store, ObjectMapper mapper) {
        this.store = store;
        this.applier = new StateEventApplier(mapper);
    }

    public EngineState rebuild() {
        EngineState state = new EngineState();
        for (AuditEvent event : store.readAll()) {
            applier.apply(state, event);
        }
        return state;
    }
}

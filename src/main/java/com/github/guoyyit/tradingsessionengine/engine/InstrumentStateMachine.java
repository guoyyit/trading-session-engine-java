package com.github.guoyyit.tradingsessionengine.engine;

import com.github.guoyyit.tradingsessionengine.model.HaltPolicy;
import com.github.guoyyit.tradingsessionengine.model.InstrumentStatus;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;

import java.util.HashMap;
import java.util.Map;

/** Halt / resume transitions and the configurable disposition strategies. */
public class InstrumentStateMachine {

    private final HaltPolicy defaultPolicy;
    private final Map<String, HaltPolicy> overrides = new HashMap<>();

    public InstrumentStateMachine(HaltPolicy defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    public void setPolicy(String instrumentId, HaltPolicy policy) {
        overrides.put(instrumentId, policy);
    }

    public HaltPolicy policyFor(String instrumentId) {
        return overrides.getOrDefault(instrumentId, defaultPolicy);
    }

    /** Strategy applied to orders already resting when the instrument halts. */
    public OrderAction restingStrategy(String instrumentId) {
        return policyFor(instrumentId).restingOrderStrategy();
    }

    /** Strategy for orders arriving while halted. */
    public OrderAction incomingOrderStrategy(String instrumentId) {
        return policyFor(instrumentId).incomingOrderStrategy();
    }

    /** Strategy for cancel requests arriving while halted. */
    public OrderAction incomingCancelStrategy(String instrumentId) {
        return policyFor(instrumentId).incomingCancelStrategy();
    }

    public boolean isHalted(InstrumentState state) {
        return state.status == InstrumentStatus.HALTED;
    }
}

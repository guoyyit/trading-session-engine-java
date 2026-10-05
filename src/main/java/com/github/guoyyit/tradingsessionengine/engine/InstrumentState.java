package com.github.guoyyit.tradingsessionengine.engine;

import com.github.guoyyit.tradingsessionengine.model.HaltPolicy;
import com.github.guoyyit.tradingsessionengine.model.HaltReason;
import com.github.guoyyit.tradingsessionengine.model.InstrumentStatus;
import com.github.guoyyit.tradingsessionengine.model.PriceBand;
import com.github.guoyyit.tradingsessionengine.model.QuoteTick;
import com.github.guoyyit.tradingsessionengine.model.RestingOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Mutable per-instrument disposition state held inside {@link EngineState}. */
public final class InstrumentState {
    public final String instrumentId;
    public InstrumentStatus status = InstrumentStatus.ACTIVE;
    public HaltReason haltReason;
    public Instant haltedAt;
    public Instant resumeAt;
    public PriceBand staticBand;
    public PriceBand dynamicBand;
    public QuoteTick lastTick;
    public boolean haltOnStaticViolation;
    public boolean haltOnDynamicViolation;
    public HaltPolicy policyOverride;
    public final List<RestingOrder> restingOrders = new ArrayList<>();
    public final List<String> queuedOrderIds = new ArrayList<>();

    public InstrumentState(String instrumentId) {
        this.instrumentId = instrumentId;
    }
}

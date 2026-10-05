package com.github.guoyyit.tradingsessionenginejava.engine;

import com.github.guoyyit.tradingsessionenginejava.circuitbreaker.CircuitBreakerPhase;
import com.github.guoyyit.tradingsessionenginejava.instrument.InstrumentTradingState;
import com.github.guoyyit.tradingsessionenginejava.pricelimit.PriceBand;
import com.github.guoyyit.tradingsessionenginejava.session.SessionPhase;

import java.time.Instant;
import java.util.Map;

/**
 * 某一时刻的自洽状态快照：读取期间不受并发迁移影响。
 */
public record EngineSnapshot(
        Instant snapshotAt,
        SessionPhase sessionPhase,
        CircuitBreakerPhase circuitBreakerPhase,
        int circuitBreakerLevel,
        Map<String, InstrumentTradingState> instrumentStates,
        Map<String, PriceBand> priceBands,
        long lastAuditSequence) {

    public EngineSnapshot {
        instrumentStates = Map.copyOf(instrumentStates);
        priceBands = Map.copyOf(priceBands);
    }
}

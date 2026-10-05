package com.github.guoyyit.tradingsessionengine.engine;

import com.github.guoyyit.tradingsessionengine.config.BreakerConfig;
import com.github.guoyyit.tradingsessionengine.config.BreakerLevelSpec;
import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** Graded market circuit breaker with mandatory halt-then-cooldown recovery. */
public class CircuitBreaker {

    private final Map<BreakerLevel, BreakerLevelSpec> specs = new EnumMap<>(BreakerLevel.class);

    public CircuitBreaker(BreakerConfig config) {
        for (BreakerLevelSpec spec : config.levels()) {
            specs.put(spec.level(), spec);
        }
    }

    public BreakerLevelSpec specFor(BreakerLevel level) {
        BreakerLevelSpec spec = specs.get(level);
        if (spec == null) {
            throw new IllegalArgumentException("no breaker level configured: " + level);
        }
        return spec;
    }

    public Instant haltEnd(Instant triggeredAt, BreakerLevel level) {
        return triggeredAt.plus(specFor(level).haltDuration());
    }

    /** Cooldown is measured from the end of the halt stage. */
    public Instant cooldownEnd(Instant haltEnd, BreakerLevel level) {
        return haltEnd.plus(specFor(level).cooldownDuration());
    }

    /** The CLOSE level has no recovery: trading ends for the day. */
    public boolean isTerminal(BreakerLevel level) {
        return level == BreakerLevel.CLOSE;
    }
}

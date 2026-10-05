package com.github.guoyyit.tradingsessionengine.config;

import com.github.guoyyit.tradingsessionengine.model.HaltPolicy;
import com.github.guoyyit.tradingsessionengine.model.OrderAction;

import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Every tunable of the engine in one place.
 *
 * @param staticBandPercentage static band half-width as a fraction of the reference price
 * @param dynamicBandPercentage dynamic band half-width as a fraction of the last price
 * @param defaultHaltPolicy policies used when an instrument has no override
 * @param breakerConfig graded circuit-breaker stages
 * @param eventLogPath file the durable audit/event log is appended to
 */
public record EngineConfig(BigDecimal staticBandPercentage,
                           BigDecimal dynamicBandPercentage,
                           HaltPolicy defaultHaltPolicy,
                           BreakerConfig breakerConfig,
                           Path eventLogPath) {

    /** Default policy: cancel resting orders, reject incoming orders, allow cancels. */
    public static HaltPolicy defaultPolicy() {
        return new HaltPolicy(OrderAction.REJECT, OrderAction.REJECT, OrderAction.ACCEPT);
    }
}

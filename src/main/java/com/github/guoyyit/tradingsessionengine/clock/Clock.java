package com.github.guoyyit.tradingsessionengine.clock;

import java.time.Instant;

/**
 * Injectable clock. All state transitions are driven by instants obtained from
 * this clock so that tests never depend on wall-clock time.
 */
public interface Clock {
    Instant now();
}

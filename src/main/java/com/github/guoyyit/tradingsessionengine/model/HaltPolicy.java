package com.github.guoyyit.tradingsessionengine.model;

/**
 * Configurable strategies applied when an instrument is halted.
 *
 * @param restingOrderStrategy what happens to orders resting before the halt
 * @param incomingOrderStrategy what happens to orders arriving while halted
 * @param incomingCancelStrategy what happens to cancel requests arriving while halted
 */
public record HaltPolicy(OrderAction restingOrderStrategy,
                         OrderAction incomingOrderStrategy,
                         OrderAction incomingCancelStrategy) {
}

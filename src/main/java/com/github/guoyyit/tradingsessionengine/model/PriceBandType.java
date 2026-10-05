package com.github.guoyyit.tradingsessionengine.model;

/** Static bands are anchored to a daily reference price; dynamic bands move with the latest quote. */
public enum PriceBandType {
    STATIC,
    DYNAMIC
}

package com.github.guoyyit.tradingsessionengine.engine;

import com.github.guoyyit.tradingsessionengine.config.EngineConfig;
import com.github.guoyyit.tradingsessionengine.model.PriceBand;
import com.github.guoyyit.tradingsessionengine.model.PriceBandType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Static/dynamic band computation and versioned, deterministic price checks. */
public class PriceLimitService {

    private static final int SCALE = 4;

    private final EngineConfig config;

    public PriceLimitService(EngineConfig config) {
        this.config = config;
    }

    /**
     * Builds an inclusive band around the reference price.
     * staticBand: ref * (1 +/- staticBandPercentage)
     * dynamicBand: ref * (1 +/- dynamicBandPercentage)
     */
    public PriceBand buildBand(PriceBandType type, BigDecimal reference, long version) {
        if (reference == null || reference.signum() <= 0) {
            throw new IllegalArgumentException("reference price must be positive");
        }
        BigDecimal percentage = type == PriceBandType.STATIC
                ? config.staticBandPercentage()
                : config.dynamicBandPercentage();
        BigDecimal delta = reference.multiply(percentage).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal lower = reference.subtract(delta);
        BigDecimal upper = reference.add(delta);
        if (lower.signum() < 0) {
            lower = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return new PriceBand(type, lower, upper, version);
    }

    /**
     * Checks one price against one immutable band version. Because the band
     * instance never changes after publication, a band move can never change
     * the verdict for an order judged against the earlier version.
     */
    public boolean isWithin(PriceBand band, BigDecimal price) {
        if (band == null || price == null) {
            return false;
        }
        return price.compareTo(band.lower()) >= 0 && price.compareTo(band.upper()) <= 0;
    }
}

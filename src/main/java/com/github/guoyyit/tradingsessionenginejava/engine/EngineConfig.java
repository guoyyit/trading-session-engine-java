package com.github.guoyyit.tradingsessionenginejava.engine;

import com.github.guoyyit.tradingsessionenginejava.instrument.HaltPolicies;

import java.util.Objects;

/**
 * 引擎配置项。
 */
public final class EngineConfig {

    private final HaltPolicies haltPolicies;
    private final boolean orderAllowedInAuction;
    private final boolean cancelAllowedInAuction;

    private EngineConfig(Builder builder) {
        this.haltPolicies = Objects.requireNonNull(builder.haltPolicies);
        this.orderAllowedInAuction = builder.orderAllowedInAuction;
        this.cancelAllowedInAuction = builder.cancelAllowedInAuction;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static EngineConfig defaults() {
        return builder().build();
    }

    public HaltPolicies haltPolicies() {
        return haltPolicies;
    }

    /** 集合竞价阶段是否接受订单 */
    public boolean orderAllowedInAuction() {
        return orderAllowedInAuction;
    }

    /** 集合竞价阶段是否接受撤单 */
    public boolean cancelAllowedInAuction() {
        return cancelAllowedInAuction;
    }

    public static final class Builder {
        private HaltPolicies haltPolicies = HaltPolicies.defaults();
        private boolean orderAllowedInAuction = true;
        private boolean cancelAllowedInAuction = true;

        public Builder haltPolicies(HaltPolicies policies) {
            this.haltPolicies = policies;
            return this;
        }

        public Builder orderAllowedInAuction(boolean allowed) {
            this.orderAllowedInAuction = allowed;
            return this;
        }

        public Builder cancelAllowedInAuction(boolean allowed) {
            this.cancelAllowedInAuction = allowed;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(this);
        }
    }
}

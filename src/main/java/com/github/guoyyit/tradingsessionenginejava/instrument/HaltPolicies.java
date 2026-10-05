package com.github.guoyyit.tradingsessionenginejava.instrument;

/**
 * 停牌处置策略集合（可配置）。
 */
public record HaltPolicies(
        RestingOrderAction restingOrderAction,
        HaltedOrderPolicy haltedOrderPolicy,
        HaltedCancelPolicy haltedCancelPolicy) {

    public static HaltPolicies defaults() {
        return new HaltPolicies(
                RestingOrderAction.CANCEL_ALL,
                HaltedOrderPolicy.REJECT,
                HaltedCancelPolicy.ACCEPT);
    }
}

package com.github.guoyyit.tradingsessionenginejava.pricelimit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 价格限制引擎：维护各品种参考价与价格带，判定订单价格是否越限。
 * 价格带变化只影响变化之后到达的订单判定，已接受订单按接受时的判定结果保留。
 */
public final class PriceLimitEngine {

    /**
     * 品种价格状态：规则、静态参考价、动态参考价（随行情移动）。
     */
    private record InstrumentPriceState(
            PriceLimitRule rule,
            BigDecimal staticReference,
            BigDecimal dynamicReference) {

        BigDecimal effectiveReference() {
            if (rule.kind() == PriceLimitRule.Kind.DYNAMIC) {
                return dynamicReference != null ? dynamicReference : staticReference;
            }
            return staticReference;
        }
    }

    private final Map<String, InstrumentPriceState> states = new HashMap<>();

    /**
     * 设置品种的价格限制规则。
     */
    public void setRule(String instrumentId, PriceLimitRule rule) {
        InstrumentPriceState old = states.get(instrumentId);
        states.put(instrumentId, new InstrumentPriceState(
                rule,
                old != null ? old.staticReference() : null,
                old != null ? old.dynamicReference() : null));
    }

    /**
     * 设置静态参考价（如前收盘价）。
     */
    public void setStaticReference(String instrumentId, BigDecimal price, Instant at) {
        InstrumentPriceState old = states.get(instrumentId);
        if (old == null) {
            return;
        }
        states.put(instrumentId, new InstrumentPriceState(old.rule(), price, old.dynamicReference()));
    }

    /**
     * 行情更新：动态规则的参考价随最新成交价移动。
     */
    public void onTradePrice(String instrumentId, BigDecimal price, Instant at) {
        InstrumentPriceState old = states.get(instrumentId);
        if (old == null || old.rule().kind() != PriceLimitRule.Kind.DYNAMIC) {
            return;
        }
        states.put(instrumentId, new InstrumentPriceState(old.rule(), old.staticReference(), price));
    }

    public Optional<PriceBand> currentBand(String instrumentId) {
        InstrumentPriceState state = states.get(instrumentId);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(state.rule().bandFor(state.effectiveReference()));
    }

    /**
     * 判定价格是否在允许范围内；无规则时默认允许。
     */
    public boolean isAllowed(String instrumentId, BigDecimal price) {
        return currentBand(instrumentId).map(band -> band.contains(price)).orElse(true);
    }

    public Map<String, PriceBand> exportBands() {
        Map<String, PriceBand> bands = new HashMap<>();
        states.forEach((id, state) -> bands.put(id, state.rule().bandFor(state.effectiveReference())));
        return bands;
    }

    public void restoreBands(Map<String, PriceBand> bands) {
        // TODO: 填充实现
    }
}

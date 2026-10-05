package com.github.guoyyit.tradingsessionenginejava.instrument;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 品种处置状态管理：停牌/复牌及停牌期间订单处置。
 */
public final class InstrumentStateManager {

    /**
     * 停牌信息：触发方式与停牌时刻。
     */
    public record HaltInfo(HaltTrigger trigger, Instant haltedAt) {
    }

    private final HaltPolicies policies;
    private final Map<String, HaltInfo> haltedInstruments = new HashMap<>();

    public InstrumentStateManager(HaltPolicies policies) {
        this.policies = policies;
    }

    public InstrumentTradingState stateOf(String instrumentId) {
        return haltedInstruments.containsKey(instrumentId)
                ? InstrumentTradingState.HALTED
                : InstrumentTradingState.TRADING;
    }

    public Optional<HaltTrigger> haltTriggerOf(String instrumentId) {
        return Optional.ofNullable(haltedInstruments.get(instrumentId)).map(HaltInfo::trigger);
    }

    public void halt(String instrumentId, HaltTrigger trigger, Instant at) {
        haltedInstruments.putIfAbsent(instrumentId, new HaltInfo(trigger, at));
    }

    public void resume(String instrumentId, Instant at) {
        haltedInstruments.remove(instrumentId);
    }

    public HaltPolicies policies() {
        return policies;
    }

    /**
     * 导出全部品种状态（用于快照/持久化）。
     */
    public Map<String, InstrumentTradingState> exportStates() {
        Map<String, InstrumentTradingState> states = new HashMap<>();
        haltedInstruments.forEach((id, info) -> states.put(id, InstrumentTradingState.HALTED));
        return states;
    }

    /**
     * 导出完整停牌信息（含触发方式，用于精确恢复）。
     */
    public Map<String, HaltInfo> exportHaltInfo() {
        return Map.copyOf(haltedInstruments);
    }

    /**
     * 从持久化恢复品种状态。
     */
    public void restoreStates(Map<String, InstrumentTradingState> states) {
        haltedInstruments.clear();
        states.forEach((id, state) -> {
            if (state == InstrumentTradingState.HALTED) {
                haltedInstruments.put(id, new HaltInfo(HaltTrigger.MANUAL, null));
            }
        });
    }

    /**
     * 从持久化恢复完整停牌信息。
     */
    public void restoreHaltInfo(Map<String, HaltInfo> haltInfo) {
        haltedInstruments.clear();
        haltedInstruments.putAll(haltInfo);
    }
}

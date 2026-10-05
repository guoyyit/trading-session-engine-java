package com.github.guoyyit.tradingsessionenginejava.order;

import com.github.guoyyit.tradingsessionenginejava.session.SessionPhase;

import java.time.Instant;

/**
 * 一次请求判定的完整记录：结果、判定时刻、归属时段与原因。
 */
public record DecisionRecord(
        OrderDecision decision,
        Instant decidedAt,
        SessionPhase phase,
        String reason) {

    public static DecisionRecord of(OrderDecision decision, Instant at, SessionPhase phase, String reason) {
        return new DecisionRecord(decision, at, phase, reason);
    }
}

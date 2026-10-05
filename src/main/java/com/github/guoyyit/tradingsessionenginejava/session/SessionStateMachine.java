package com.github.guoyyit.tradingsessionenginejava.session;

import com.github.guoyyit.tradingsessionenginejava.clock.TradingClock;

import java.time.Instant;
import java.util.Optional;

/**
 * 时段状态机：由注入时钟驱动，相同的时钟序列产生逐次一致的状态迁移。
 */
public final class SessionStateMachine {

    private final TradingClock clock;
    private DaySchedule schedule;
    private SessionPhase currentPhase = SessionPhase.CLOSED;
    private Instant phaseEnteredAt;

    public SessionStateMachine(TradingClock clock, DaySchedule schedule) {
        this.clock = clock;
        this.schedule = schedule;
    }

    /**
     * 根据当前时钟推进状态，返回是否发生迁移。
     */
    public boolean advance() {
        Instant now = clock.now();
        SessionPhase target = schedule.windowAt(now)
                .map(SessionWindow::phase)
                .orElse(SessionPhase.CLOSED);
        if (target == currentPhase) {
            return false;
        }
        currentPhase = target;
        phaseEnteredAt = now;
        return true;
    }

    public SessionPhase currentPhase() {
        return currentPhase;
    }

    public Instant phaseEnteredAt() {
        return phaseEnteredAt;
    }

    public Optional<SessionWindow> currentWindow() {
        return schedule.windowAt(clock.now());
    }

    public void resetSchedule(DaySchedule newSchedule) {
        this.schedule = newSchedule;
    }
}

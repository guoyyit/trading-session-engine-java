package com.github.guoyyit.tradingsessionengine.engine;

import com.github.guoyyit.tradingsessionengine.config.SessionSchedule;
import com.github.guoyyit.tradingsessionengine.config.SessionSegment;
import com.github.guoyyit.tradingsessionengine.model.MarketPhase;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/** Computes schedule-driven phase transitions from injected clock instants. */
public class SessionStateMachine {

    private final SessionSchedule schedule;
    private final ZoneId zone;

    public SessionStateMachine(SessionSchedule schedule) {
        this.schedule = schedule;
        this.zone = schedule.zone();
    }

    public SessionStateMachine(SessionSchedule schedule, ZoneId zone) {
        this.schedule = schedule;
        this.zone = zone;
    }

    /** Phase that the schedule mandates at the given instant. */
    public MarketPhase phaseAt(Instant now) {
        LocalTime time = LocalTime.from(now.atZone(zone));
        for (SessionSegment segment : schedule.segments()) {
            // Half-open [start, end): a boundary instant belongs to the later
            // segment, giving every request a unique phase attribution.
            if (!time.isBefore(segment.start()) && time.isBefore(segment.end())) {
                return segment.phase();
            }
        }
        if (!schedule.segments().isEmpty()) {
            SessionSegment last = schedule.segments().get(schedule.segments().size() - 1);
            if (!time.isBefore(last.end())) {
                return MarketPhase.CLOSED;
            }
        }
        return MarketPhase.PRE_OPEN;
    }
}

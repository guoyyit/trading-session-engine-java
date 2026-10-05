package com.github.guoyyit.tradingsessionenginejava.tests;

import com.github.guoyyit.tradingsessionenginejava.clock.ManualClock;
import com.github.guoyyit.tradingsessionenginejava.session.DaySchedule;
import com.github.guoyyit.tradingsessionenginejava.session.SessionPhase;
import com.github.guoyyit.tradingsessionenginejava.session.SessionStateMachine;
import com.github.guoyyit.tradingsessionenginejava.session.SessionWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时段状态机：完整迁移序列与相同时钟序列重放一致性。
 */
class SessionStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");

    static DaySchedule standardDay() {
        return new DaySchedule(List.of(
                new SessionWindow(SessionPhase.OPENING_AUCTION, T0.plusSeconds(3600 * 2), T0.plusSeconds(3600 * 3)),
                new SessionWindow(SessionPhase.CONTINUOUS_TRADING, T0.plusSeconds(3600 * 3), T0.plusSeconds(3600 * 5)),
                new SessionWindow(SessionPhase.BREAK, T0.plusSeconds(3600 * 5), T0.plusSeconds(3600 * 6)),
                new SessionWindow(SessionPhase.CONTINUOUS_TRADING, T0.plusSeconds(3600 * 6), T0.plusSeconds(3600 * 8)),
                new SessionWindow(SessionPhase.CLOSING_AUCTION, T0.plusSeconds(3600 * 8), T0.plusSeconds(3600 * 8 + 600))));
    }

    @Test
    void fullDayTransitions() {
        ManualClock clock = new ManualClock(T0);
        SessionStateMachine machine = new SessionStateMachine(clock, standardDay());

        assertEquals(SessionPhase.CLOSED, machine.currentPhase());

        List<SessionPhase> expected = List.of(
                SessionPhase.OPENING_AUCTION,
                SessionPhase.CONTINUOUS_TRADING,
                SessionPhase.BREAK,
                SessionPhase.CONTINUOUS_TRADING,
                SessionPhase.CLOSING_AUCTION,
                SessionPhase.CLOSED);
        long[] offsets = {3600 * 2, 3600 * 3, 3600 * 5, 3600 * 6, 3600 * 8, 3600 * 8 + 600};

        for (int i = 0; i < expected.size(); i++) {
            clock.set(T0.plusSeconds(offsets[i]));
            assertTrue(machine.advance(), "expected transition at offset " + offsets[i]);
            assertEquals(expected.get(i), machine.currentPhase());
            assertEquals(T0.plusSeconds(offsets[i]), machine.phaseEnteredAt());
        }
    }

    @Test
    void sameClockSequenceProducesIdenticalTransitions() {
        List<Instant> clockSequence = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            clockSequence.add(T0.plusSeconds(i * 300));
        }

        List<SessionPhase> run1 = replay(clockSequence);
        List<SessionPhase> run2 = replay(clockSequence);
        assertEquals(run1, run2);
    }

    private List<SessionPhase> replay(List<Instant> clockSequence) {
        ManualClock clock = new ManualClock(clockSequence.get(0));
        SessionStateMachine machine = new SessionStateMachine(clock, standardDay());
        List<SessionPhase> phases = new ArrayList<>();
        for (Instant instant : clockSequence) {
            clock.set(instant);
            machine.advance();
            phases.add(machine.currentPhase());
        }
        return phases;
    }

    @Test
    void gapBetweenWindowsIsClosed() {
        ManualClock clock = new ManualClock(T0.plusSeconds(3600 * 3 + 600));
        SessionStateMachine machine = new SessionStateMachine(clock, new DaySchedule(List.of(
                new SessionWindow(SessionPhase.CONTINUOUS_TRADING, T0, T0.plusSeconds(3600)),
                new SessionWindow(SessionPhase.CONTINUOUS_TRADING, T0.plusSeconds(3600 * 2), T0.plusSeconds(3600 * 3)))));
        machine.advance();
        assertEquals(SessionPhase.CLOSED, machine.currentPhase());
    }
}

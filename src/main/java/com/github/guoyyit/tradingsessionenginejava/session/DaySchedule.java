package com.github.guoyyit.tradingsessionenginejava.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 交易日时段表：由不重叠、按时间升序排列的窗口组成。
 * 窗口之外的间隙视为 CLOSED。
 */
public final class DaySchedule {

    private final List<SessionWindow> windows;

    public DaySchedule(List<SessionWindow> windows) {
        this.windows = List.copyOf(windows);
    }

    public List<SessionWindow> windows() {
        return windows;
    }

    /**
     * 查询某时刻所处的窗口；不在任何窗口内时返回空（即 CLOSED）。
     */
    public Optional<SessionWindow> windowAt(Instant instant) {
        for (SessionWindow window : windows) {
            if (window.contains(instant)) {
                return Optional.of(window);
            }
        }
        return Optional.empty();
    }
}

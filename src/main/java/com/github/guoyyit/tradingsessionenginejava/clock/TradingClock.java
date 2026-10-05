package com.github.guoyyit.tradingsessionenginejava.clock;

import java.time.Instant;

/**
 * 注入时钟：所有状态迁移只依赖该时钟，测试可注入固定时间序列。
 */
public interface TradingClock {

    Instant now();
}

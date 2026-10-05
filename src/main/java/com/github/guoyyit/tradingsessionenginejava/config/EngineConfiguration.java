package com.github.guoyyit.tradingsessionenginejava.config;

import com.github.guoyyit.tradingsessionenginejava.audit.AuditLog;
import com.github.guoyyit.tradingsessionenginejava.audit.FileAuditLog;
import com.github.guoyyit.tradingsessionenginejava.clock.SystemClock;
import com.github.guoyyit.tradingsessionenginejava.clock.TradingClock;
import com.github.guoyyit.tradingsessionenginejava.engine.EngineConfig;
import com.github.guoyyit.tradingsessionenginejava.engine.TradingStateEngine;
import com.github.guoyyit.tradingsessionenginejava.session.DaySchedule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * 引擎 Spring 装配：生产环境使用系统时钟与文件审计日志。
 */
@Configuration
public class EngineConfiguration {

    @Bean
    public TradingClock tradingClock() {
        return new SystemClock();
    }

    @Bean
    public AuditLog auditLog(TradingClock clock,
                             @Value("${engine.audit-file:data/audit.log}") String auditFile) {
        return new FileAuditLog(clock, Path.of(auditFile));
    }

    @Bean
    public TradingStateEngine tradingStateEngine(TradingClock clock, AuditLog auditLog) {
        return new TradingStateEngine(clock, EngineConfig.defaults(),
                new DaySchedule(List.of()), auditLog, List.of());
    }
}

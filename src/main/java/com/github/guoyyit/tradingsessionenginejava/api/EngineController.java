package com.github.guoyyit.tradingsessionenginejava.api;

import com.github.guoyyit.tradingsessionenginejava.engine.EngineSnapshot;
import com.github.guoyyit.tradingsessionenginejava.engine.TradingStateEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 状态查询 REST 接口。
 */
@RestController
@RequestMapping("/api/engine")
public class EngineController {

    private final TradingStateEngine engine;

    public EngineController(TradingStateEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        EngineSnapshot snapshot = engine.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotAt", snapshot.snapshotAt().toString());
        body.put("sessionPhase", snapshot.sessionPhase().name());
        body.put("circuitBreakerPhase", snapshot.circuitBreakerPhase().name());
        body.put("circuitBreakerLevel", snapshot.circuitBreakerLevel());
        body.put("instrumentStates", snapshot.instrumentStates());
        body.put("priceBands", snapshot.priceBands());
        body.put("lastAuditSequence", snapshot.lastAuditSequence());
        return body;
    }
}

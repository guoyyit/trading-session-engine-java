package com.github.guoyyit.tradingsessionengine.web;

import com.github.guoyyit.tradingsessionengine.model.BreakerLevel;
import com.github.guoyyit.tradingsessionengine.model.CancelRequest;
import com.github.guoyyit.tradingsessionengine.model.Decision;
import com.github.guoyyit.tradingsessionengine.model.HaltReason;
import com.github.guoyyit.tradingsessionengine.model.MarketSnapshot;
import com.github.guoyyit.tradingsessionengine.model.OrderRequest;
import com.github.guoyyit.tradingsessionengine.model.QuoteTick;
import com.github.guoyyit.tradingsessionengine.service.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints exposing session state and the disposition operations. */
@RestController
@RequestMapping("/api/trading")
public class TradingController {

    private final TradingService service;

    public TradingController(TradingService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    public MarketSnapshot snapshot() {
        return service.snapshot();
    }

    @PostMapping("/clock/tick")
    public void tick() {
        service.advanceClock();
    }

    @PostMapping("/orders")
    public Decision order(@RequestBody OrderRequest request) {
        return service.submitOrder(request);
    }

    @PostMapping("/cancels")
    public Decision cancel(@RequestBody CancelRequest request) {
        return service.cancel(request);
    }

    @PostMapping("/quotes")
    public void quote(@RequestBody QuoteTick tick) {
        service.quote(tick);
    }

    @PostMapping("/instruments/{instrumentId}/halt/{reason}")
    public void halt(@PathVariable String instrumentId, @PathVariable String reason) {
        service.halt(instrumentId, HaltReason.valueOf(reason));
    }

    @PostMapping("/instruments/{instrumentId}/resume")
    public void resume(@PathVariable String instrumentId) {
        service.resume(instrumentId);
    }

    @PostMapping("/breakers/{level}")
    public void triggerBreaker(@PathVariable String level) {
        service.triggerBreaker(BreakerLevel.valueOf(level));
    }
}

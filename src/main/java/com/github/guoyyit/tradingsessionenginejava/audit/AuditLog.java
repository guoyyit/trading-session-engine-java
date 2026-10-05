package com.github.guoyyit.tradingsessionenginejava.audit;

import java.util.List;
import java.util.Map;

/**
 * 审计日志：追加写入，支持读取用于重放。
 */
public interface AuditLog {

    /**
     * 追加一条审计事件，返回分配的序号（严格递增）。
     */
    long append(AuditEventType type, Map<String, String> payload);

    /**
     * 读取全部事件（按序号升序）。
     */
    List<AuditEvent> readAll();

    /**
     * 当前最大序号；无事件时为 0。
     */
    long lastSequence();
}

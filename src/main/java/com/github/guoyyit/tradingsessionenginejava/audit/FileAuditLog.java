package com.github.guoyyit.tradingsessionenginejava.audit;

import com.github.guoyyit.tradingsessionenginejava.clock.TradingClock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件审计日志：每条事件一行，追加写并刷盘，崩溃后已确认事件不丢失。
 * 行格式：sequence|epochMilli|TYPE|k1=v1,k2=v2（特殊字符百分号转义）。
 */
public final class FileAuditLog implements AuditLog {

    private final TradingClock clock;
    private final Path file;
    private long lastSequence;

    public FileAuditLog(TradingClock clock, Path file) {
        this.clock = clock;
        this.file = file;
        this.lastSequence = readAll().stream().mapToLong(AuditEvent::sequence).max().orElse(0);
    }

    @Override
    public synchronized long append(AuditEventType type, Map<String, String> payload) {
        lastSequence++;
        AuditEvent event = new AuditEvent(lastSequence, clock.now(), type, payload);
        StringBuilder line = new StringBuilder()
                .append(event.sequence()).append('|')
                .append(event.occurredAt().toEpochMilli()).append('|')
                .append(event.type().name()).append('|');
        boolean first = true;
        for (Map.Entry<String, String> entry : event.payload().entrySet()) {
            if (!first) {
                line.append(',');
            }
            line.append(escape(entry.getKey())).append('=').append(escape(entry.getValue()));
            first = false;
        }
        line.append('\n');
        try {
            Files.writeString(file, line.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append audit event", e);
        }
        return lastSequence;
    }

    @Override
    public synchronized List<AuditEvent> readAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<AuditEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|", 4);
                long sequence = Long.parseLong(parts[0]);
                Instant occurredAt = Instant.ofEpochMilli(Long.parseLong(parts[1]));
                AuditEventType type = AuditEventType.valueOf(parts[2]);
                Map<String, String> payload = new LinkedHashMap<>();
                if (parts.length == 4 && !parts[3].isEmpty()) {
                    for (String pair : splitUnescaped(parts[3])) {
                        int eq = indexOfUnescaped(pair, '=');
                        payload.put(unescape(pair.substring(0, eq)), unescape(pair.substring(eq + 1)));
                    }
                }
                events.add(new AuditEvent(sequence, occurredAt, type, payload));
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read audit log", e);
        }
    }

    @Override
    public synchronized long lastSequence() {
        return lastSequence;
    }

    private static String escape(String value) {
        return value.replace("%", "%25")
                .replace("|", "%7C")
                .replace(",", "%2C")
                .replace("=", "%3D")
                .replace("\n", "%0A");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 <= value.length() - 1) {
                result.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static List<String> splitUnescaped(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' ) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
                if (c == '%' && i + 2 <= value.length() - 1) {
                    current.append(value, i + 1, i + 3);
                    i += 2;
                }
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static int indexOfUnescaped(String value, char target) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == target) {
                return i;
            }
            if (c == '%') {
                i += 2;
            }
        }
        return -1;
    }
}

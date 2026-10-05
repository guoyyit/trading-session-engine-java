package com.github.guoyyit.tradingsessionengine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * File-backed event store. Each line is one JSON-serialized event; a batch is
 * written in one write call and made durable with fsync, so recovery only ever
 * sees complete lines (a torn tail line from a crash is ignored).
 */
public class FileEventStore implements EventStore {

    private final Path path;
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private long lastSequence;

    public FileEventStore(Path path) {
        this.path = path;
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            if (Files.notExists(path)) {
                Files.createFile(path);
            } else {
                this.lastSequence = scanLastSequence();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void appendBatch(List<AuditEvent> batch) {
        StringBuilder builder = new StringBuilder();
        long expected = lastSequence;
        for (AuditEvent event : batch) {
            if (event.sequence() != expected + 1) {
                throw new IllegalStateException("non-contiguous event sequence: expected "
                        + (expected + 1) + " got " + event.sequence());
            }
            expected = event.sequence();
            try {
                builder.append(mapper.writeValueAsString(event)).append('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel out = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                out.write(buffer);
            }
            out.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        lastSequence = expected;
    }

    @Override
    public synchronized List<AuditEvent> readAll() {
        List<AuditEvent> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                AuditEvent event;
                try {
                    event = mapper.readValue(line, AuditEvent.class);
                } catch (IOException parseFailure) {
                    // Torn tail line from a crash mid-write: stop reading. All
                    // earlier complete events remain valid.
                    break;
                }
                result.add(event);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    @Override
    public synchronized long lastSequence() {
        return lastSequence;
    }

    @Override
    public synchronized void flush() {
    }

    private long scanLastSequence() throws IOException {
        long sequence = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    sequence = mapper.readValue(line, AuditEvent.class).sequence();
                } catch (IOException tornTail) {
                    break;
                }
            }
        }
        return sequence;
    }
}

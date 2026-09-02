package com.assessment.orchestrator.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Audit-grade, append-only log of every governance-relevant event in a run: stage transitions,
 * retries, rollbacks, policy checks, approvals, re-plans, safe-stops. Written as JSON Lines to
 * {@code <runOutputDir>/audit.jsonl} (one durable, greppable record per event) and mirrored to
 * the console for interactive visibility.
 */
public final class AuditLogger {

    private final Path logFile;
    private final ObjectMapper mapper;
    private final List<AuditEvent> inMemory = new ArrayList<>();
    private final boolean consoleEcho;

    public AuditLogger(Path runOutputDir, boolean consoleEcho) {
        this.consoleEcho = consoleEcho;
        try {
            Files.createDirectories(runOutputDir);
            this.logFile = runOutputDir.resolve("audit.jsonl");
            Files.deleteIfExists(logFile);
            Files.createFile(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public synchronized void log(AuditEvent event) {
        inMemory.add(event);
        try {
            String line = mapper.writeValueAsString(event);
            Files.writeString(logFile, line + System.lineSeparator(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (consoleEcho) {
            String stage = event.stageId() == null ? "-" : event.stageId().name();
            System.out.printf("[%-28s] [%-24s] %-20s %s%n",
                    event.timestamp(), stage, event.type(), event.message());
        }
    }

    public void log(String runId, int planVersion, AuditEventType type, StageId stageId, String message) {
        log(new AuditEvent(java.time.Instant.now(), runId, planVersion, type, stageId, message, Map.of()));
    }

    public void log(String runId, int planVersion, AuditEventType type, StageId stageId, String message, Map<String, Object> details) {
        log(new AuditEvent(java.time.Instant.now(), runId, planVersion, type, stageId, message, details));
    }

    public List<AuditEvent> getEvents() {
        return List.copyOf(inMemory);
    }

    public Path getLogFile() {
        return logFile;
    }
}

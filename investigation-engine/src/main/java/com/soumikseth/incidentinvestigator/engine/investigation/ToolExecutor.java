package com.soumikseth.incidentinvestigator.engine.investigation;

import com.soumikseth.incidentinvestigator.engine.config.InvestigationProperties;
import com.soumikseth.incidentinvestigator.engine.domain.Evidence;
import com.soumikseth.incidentinvestigator.engine.tools.InvestigationTool;
import com.soumikseth.incidentinvestigator.engine.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes one allowlisted tool with a timeout and a bounded retry.
 *
 * Failures never escape as exceptions: every outcome becomes a
 * {@link ToolExecution} so that the orchestrator can decide whether to continue,
 * retry, or abort. That distinction matters because a telemetry backend being
 * briefly unavailable should degrade the investigation, not crash it.
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ExecutorService executor;
    private final InvestigationProperties properties;

    public ToolExecutor(
            @Qualifier("investigationToolExecutor") ExecutorService executor,
            InvestigationProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    public ToolExecution execute(
            InvestigationTool tool, Map<String, Object> arguments, EvidenceLedger ledger) {
        Instant startedAt = Instant.now();
        int attempts = properties.toolRetries() + 1;
        ToolCallFailure lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ToolResult result = invokeWithTimeout(tool, arguments);
                List<Evidence> evidence = ledger.record(result.evidence());
                return ToolExecution.succeeded(
                                tool.name(), arguments, result, startedAt, Instant.now())
                        .withEvidence(evidence);
            } catch (IllegalArgumentException invalidArguments) {
                // Retrying a malformed request cannot succeed; fail immediately.
                return ToolExecution.failed(
                        tool.name(),
                        arguments,
                        TelemetrySanitizer.content(
                                "Invalid tool arguments: " + invalidArguments.getMessage()),
                        startedAt,
                        Instant.now());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ToolExecution.failed(
                        tool.name(), arguments, "Tool execution was interrupted", startedAt, Instant.now());
            } catch (ToolCallFailure failure) {
                lastFailure = failure;
                if (!failure.retryable() || attempt == attempts) {
                    break;
                }
                log.warn("Tool {} failed on attempt {}/{}: {}", tool.name(), attempt, attempts, failure.getMessage());
            }
        }

        String summary = lastFailure == null
                ? "Tool execution failed"
                : lastFailure.getMessage();
        return ToolExecution.failed(
                tool.name(), arguments, TelemetrySanitizer.content(summary), startedAt, Instant.now());
    }

    private ToolResult invokeWithTimeout(InvestigationTool tool, Map<String, Object> arguments)
            throws InterruptedException, ToolCallFailure {
        Future<ToolResult> future = executor.submit(() -> tool.execute(arguments));
        try {
            return future.get(properties.toolTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new ToolCallFailure(
                    "Tool " + tool.name() + " timed out after " + properties.toolTimeoutMs() + " ms", true);
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            if (cause instanceof IllegalArgumentException invalidArguments) {
                throw invalidArguments;
            }
            String detail = cause == null || cause.getMessage() == null
                    ? "unknown error"
                    : cause.getMessage();
            throw new ToolCallFailure("Tool " + tool.name() + " failed: " + detail, true);
        }
    }

    private static final class ToolCallFailure extends Exception {

        private final boolean retryable;

        private ToolCallFailure(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        private boolean retryable() {
            return retryable;
        }
    }
}

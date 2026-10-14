package com.library.catalog.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@ConfigurationProperties("library.outbox")
@Validated
public record OutboxProperties(
        @Min(1) @Max(100) int batchSize,
        @Min(1) @Max(100) int maxMessageFailures,
        @Min(1) @Max(30000) long confirmTimeoutMs,
        @Min(1) @Max(3600000) long retryDelayMs,
        @Min(1) @Max(86400000) long maxRetryDelayMs) {
    public long retryDelay(long attempts) {
        return Math.min(maxRetryDelayMs, retryDelayMs * (1L << Math.min(Math.max(0, attempts - 1), 20)));
    }
}

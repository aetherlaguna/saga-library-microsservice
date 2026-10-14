package com.library.catalog.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("catalog.consumer")
@Validated
public record ConsumerProperties(@Min(100) @Max(10000) long retryBackoffMs) {}

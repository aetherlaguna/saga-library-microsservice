package com.library.catalog.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoanRequested(UUID memberId, List<UUID> bookIds, Instant dueAt) {}

package com.vijay.mall.common.outbox;

import java.util.UUID;

/** Published as a Spring event after it is read from the outbox table. Listeners parse the JSON payload. */
public record OutboxMessage(UUID id, String type, String payload) {}

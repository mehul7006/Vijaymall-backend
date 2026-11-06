package com.vijay.mall.common.live;

import java.time.Instant;
import java.util.UUID;

/** A customer's credit (udhar) balance or limit changed; only that customer's streams hear it. */
public record CreditChanged(UUID customerId, String reason, long deltaPaise, Instant at) {}

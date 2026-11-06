package com.vijay.mall.common.live;

import java.time.Instant;
import java.util.UUID;

/** Published inside the transaction that changes a customer's coin balance; the live stream sends it after commit. */
public record WalletChanged(UUID userId, String reason, int delta, Instant at) {}

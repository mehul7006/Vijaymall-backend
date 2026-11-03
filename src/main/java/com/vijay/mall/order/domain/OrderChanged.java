package com.vijay.mall.order.domain;

import java.time.Instant;
import java.util.UUID;

/** Published inside the order transaction when an order is placed or changes status; the live stream sends it after commit. */
public record OrderChanged(UUID orderId, String orderNo, UUID userId, String status, String previousStatus, Instant at) {}

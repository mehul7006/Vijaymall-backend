package com.vijay.mall.catalog.domain;

import java.time.Instant;
import java.util.UUID;

public record HotBanner(UUID id, String title, String subtitle, String imageUrl, Instant startAt, Instant endAt,
                        boolean active) {}

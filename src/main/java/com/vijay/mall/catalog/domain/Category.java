package com.vijay.mall.catalog.domain;

import java.util.UUID;

public record Category(UUID id, String nameEn, String nameGu, String imageUrl, int sortOrder) {}

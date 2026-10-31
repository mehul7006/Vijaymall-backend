package com.vijay.mall.identity.domain;

import java.util.UUID;

public record Address(UUID id, String label, String line1, String area, String pincode, boolean isDefault) {}

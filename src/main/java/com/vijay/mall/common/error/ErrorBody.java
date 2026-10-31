package com.vijay.mall.common.error;

import java.util.Map;

public record ErrorBody(String code, String message, String traceId, Map<String, Object> details) {}

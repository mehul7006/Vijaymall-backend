package com.vijay.mall.common.error;

import java.util.Map;

/**
 * The JSON error shape every failed request returns.
 * @param code stable error code
 * @param message human-readable message
 * @param traceId the id from the {@code X-Trace-Id} header, to find the request in the logs
 * @param details extra values, empty when none
 */
public record ErrorBody(String code, String message, String traceId, Map<String, Object> details) {}

package com.vijay.mall.payment.domain;

import java.util.Map;

/** What the app needs to start paying. type tells the app which screen to open. */
public record PaymentSession(String type, Map<String, Object> payload) {}

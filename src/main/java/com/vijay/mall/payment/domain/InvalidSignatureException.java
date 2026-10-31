package com.vijay.mall.payment.domain;

public class InvalidSignatureException extends RuntimeException {
  public InvalidSignatureException(String message) { super(message); }
}

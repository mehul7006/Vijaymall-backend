package com.vijay.mall.payment.domain;

/**
 * Thrown by a gateway when a webhook's signature does not match or its body cannot be read; the service answers {@code 400 BAD_SIGNATURE}.
 */
public class InvalidSignatureException extends RuntimeException {
  /**
   * Creates the exception.
   * @param message what was wrong
   */
  public InvalidSignatureException(String message) { super(message); }
}

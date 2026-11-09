package com.vijay.mall.common.security;

import org.springframework.security.core.AuthenticationException;

/**
 * Thrown by the token check when the account lookup could not get a database connection in time. It is not a sign-in failure: the
 * authentication entry point answers it with {@code 503 SERVICE_BUSY} and {@code Retry-After}, and the request is never let through.
 */
class DatabaseBusyAuthenticationException extends AuthenticationException {
  DatabaseBusyAuthenticationException(Throwable cause) { super("database busy", cause); }
}

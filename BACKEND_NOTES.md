# Vijay Super Mall backend - notes

## Run it
1. PostgreSQL 16 database `mall` (user/password `mall`), or set `DB_URL`, `DB_USER`, `DB_PASSWORD`.
2. `mvn spring-boot:run -Dspring-boot.run.profiles=dev` (JDK 25; on JDK 21 add `-Djava.release=21`).
3. Dev profile: login code is always `12345`; `admin@vijaysupermall.local` becomes ADMIN; sample shop data is created once.
4. API docs: http://localhost:8080/swagger-ui.html (disabled in prod). Contract: `../API_CONTRACT.md`.

## Tests
- `mvn test` runs the unit tests (no Docker).
- `mvn verify` also runs `*IT` tests with Testcontainers (needs Docker): `OrderFlowIT` covers login, quote, order, idempotency, delivery and coin credit.

## Settings (environment variables)
`DB_URL DB_USER DB_PASSWORD JWT_SECRET OTP_CHANNEL(console|email) OTP_PEPPER SMTP_HOST SMTP_PORT SMTP_USER SMTP_PASSWORD
OTP_FROM ADMIN_EMAIL IMAGES_DIR RAZORPAY_KEY_ID RAZORPAY_KEY_SECRET RAZORPAY_WEBHOOK_SECRET`.
In production set a long random `JWT_SECRET` and `OTP_PEPPER`, `OTP_CHANNEL=email`, and `ADMIN_EMAIL` for the first admin.

## Delivery fee
Delivery is free for now: `app.delivery.fee-paise` defaults to 0 (env `DELIVERY_FEE_PAISE`; 4000 = Rs 40). `app.delivery.free-above-paise` (49900) only matters once a fee is set. An admin-editable rule is planned.

## Where things are
Modular monolith, one package per module (`api`, `app`, `domain`, `infra`). Modules call each other only through `*Service` classes
or Spring events (payment -> order uses `PaymentEvents`; notifications use the outbox table).
- Add a payment provider: one class implementing `PaymentGateway` + set `app.payment.online-provider`.
- Add a discount: one class implementing `QuoteStep` with an `order()` number.
- Add an OTP channel (SMS): implement `OtpSender`, select by `app.otp.channel`.
- Push messages: replace `LoggingPushSender` with an FCM implementation of `PushSender`.

## Stubs and limits
- Push is only logged. SMS login is not built (DLT cost). Razorpay code is written against its public HTTP API but has not been run against a live account.
- Webhook URL to register at Razorpay: `https://<your-domain>/api/v1/payments/webhook/razorpay` (events payment.captured, payment.failed).

## Deviations from the design doc
- Spring JDBC (`JdbcClient`) instead of JPA; explicit SQL for stock and coins.
- Spring Boot 3.5.6 instead of 4.x. Images are saved as `<uuid>_400.jpg` and `<uuid>_1000.jpg` (JDK has no WebP writer); the API returns the `_400` path and the app swaps to `_1000` for the detail view.
- Login is email OTP, not mobile SMS.

## Not compiled here
This code was written in a sandbox with JDK 21 but no Maven Central, so only the pure classes (`wallet/domain`, `order/domain`,
`payment/domain`, `RazorpaySignature`) were compiled and checked. Run `mvn test` first and send me the first compiler errors;
the likeliest trouble spots are `OrderViews.AdminView` (`@JsonUnwrapped` on a record), `RazorpayGateway` (RestClient calls),
`ReportService` SQL and the `JdbcClient` parameter binding of nullable values.

---
## Update: stack moved to JPA, Spring Boot 4.1.2, Java 27

- Persistence is now JPA/Hibernate 7 (no JdbcClient left). Domain stays as pure records; `infra` holds package-private `@Entity` classes, Spring Data `*Jpa` interfaces and public `@Repository` wrappers. Schema is still owned by Flyway; Hibernate runs with `ddl-auto: validate`.
- Atomic stock/coupon updates use `@Modifying` JPQL; wallet uses `PESSIMISTIC_WRITE`; orders and wallet balance use `@Version`; outbox relay uses `FOR UPDATE SKIP LOCKED` via lock timeout -2.
- Jackson 3 (`tools.jackson`), `JsonMapper`; `JsonNode.asString()`.
- Java 27 (`java.release`; use `-Djava.release=25` for the LTS JDK). Language features used: records, sealed types, pattern-matching switch, unnamed `_`, immutable folds with `reduce`, lazy rule evaluation with streams, Optional pipelines.
- NOT COMPILED HERE: this sandbox has JDK 21 and no Maven Central. Only pure-Java classes were compiled and run (LotAllocator, CouponRules etc.). Run `mvn test` and send me any errors. Likely trouble spots: BaseEntity/Persistable, JPQL `nulls last`, Jackson 3 API names, `@JsonUnwrapped` on a record.
- Integration tests (Testcontainers 2, `mvn verify`, needs Docker): OrderFlowIT, SecurityIT, AuthFlowIT, InventoryConcurrencyIT, OrderCancelIT on a shared `IntegrationTestBase`. Not yet run. Still to add: WalletLedgerIT, PaymentWebhookIT, OrderExpiryIT.

---
## Tests and coverage

Run everything and check coverage: `mvn verify` (Docker must be running for the integration tests).

- Unit tests (surefire, `*Test.java`): pure logic and services with Mockito. Fast, no Docker.
- Integration tests (failsafe, `*IT.java`): real PostgreSQL through Testcontainers and real HTTP, one shared container. Base class: `support/IntegrationTestBase`.
- JaCoCo merges both into `target/jacoco.exec`; the report is `target/site/jacoco/index.html`.
- The build FAILS if line coverage is below 98% or branch coverage below 95% (see `jacoco-maven-plugin` in pom.xml). Only `MallApplication` is excluded.
- These numbers have NOT been measured yet: the sandbox I work in cannot run Maven. The first `mvn verify` on your machine will show the real figure; send me the list of uncovered lines and I will add tests for them.

Integration test map: OrderFlowIT, OrderApiIT, OrderCancelIT (orders); OnlinePaymentIT (second payment provider, refunds, expiry); WalletLedgerIT; CouponApiIT; CatalogApiIT; IdentityApiIT, AuthFlowIT; SecurityIT, ErrorHandlingIT; InventoryConcurrencyIT.

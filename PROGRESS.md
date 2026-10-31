# Backend build progress (update as modules complete)
Decision: persistence is Spring JDBC (JdbcClient) not JPA: fewer annotation/mapping risks, explicit SQL for stock/coins.
- [x] pom, pure domain slice (CoinMath, OrderStatus)
- [x] common (errors, security/JWT, rate limit, db helpers)
- [x] V1 migration
- [x] identity  - [x] catalog+inventory  - [x] promotion  - [x] wallet  - [x] quote  - [x] order  - [x] payment
- [x] notification+outbox  - [x] report  - [x] seed  - [x] config+Dockerfile  - [x] tests (unit; *IT pending)  - [x] notes

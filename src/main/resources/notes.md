# `resources/` — Configuration & Seed Data

## Purpose
Everything Spring Boot loads at startup that isn't Java code: datasource credentials, JPA/Hibernate
behavior flags, OAuth2 client registrations, and optional SQL seed data.

## Files in this project

### `application.properties`
```
spring.application.name=hospitalManagement
spring.datasource.url=jdbc:postgresql://localhost:5432/hospitalDB
spring.datasource.username=
spring.datasource.password=
server.servlet.context-path=/api/v1
spring.jpa.hibernate.ddl-auto=create
spring.jpa.show-sql=true
jwt.secretKey=...
```

- **`spring.jpa.hibernate.ddl-auto=create`** — Hibernate drops and recreates every table on every
  application restart, from the `@Entity` class definitions. This is why the entity annotations
  (`@Column`, `@Table`, `@Index`, `@UniqueConstraint`) in `entity/` directly control the generated schema —
  there's no separate migration file to keep in sync. **Never use `create` in production** (`validate` or
  a migration tool like Flyway/Liquibase is the production-safe choice); it's a deliberate convenience for
  a learning project where the schema is still evolving.
- **`spring.jpa.show-sql=true`** — logs every SQL statement Hibernate generates. This is the fastest way to
  *see* the difference between a derived query method, a JPQL query, and dirty-checking's automatic
  UPDATE — turn this on and watch the console while exercising `PatientRepository` or
  `AppointmentService.reAssignAppointmentToAnotherDoctor`.
- **`jwt.secretKey`** — the HMAC signing key `AuthUtil` uses to sign/verify JWTs, injected via `@Value`.
  Hardcoded in plaintext here only because this is a learning project — in a real deployment this belongs
  in an environment variable or secrets manager, never committed to source control.
- **`server.servlet.context-path=/api/v1`** — every controller mapping (`/auth`, `/patients`, `/doctors`,
  `/admin`, `/public`) is actually served under `/api/v1/...`. Easy to forget when testing endpoints.

### `application.yml`
OAuth2 client registrations for Google, GitHub, and Twitter (`spring.security.oauth2.client.registration.*`),
plus a manual `provider.twitter` block (Twitter isn't a Spring Security-recognized default provider, so its
authorization/token/user-info endpoints must be declared explicitly — Google and GitHub are recognized
defaults and don't need this). These registrations are what `WebSecurityConfig`'s `.oauth2Login(...)` and
`OAuth2SuccessHandler` operate on. Client secrets are inlined here for the same learning-project reason as
`jwt.secretKey` above — not a pattern to copy into a real deployment.

### `data.sql`
Seed `INSERT` statements for `patient`, `doctor`, and `appointment` tables. Note: by default Spring Boot only
auto-runs `data.sql` for embedded databases; against an external PostgreSQL instance (as configured here) it
requires `spring.sql.init.mode=always` (and typically `spring.jpa.defer-datasource-initialization=true` so
Hibernate creates the tables from `ddl-auto=create` *before* this script runs) — both of which are present but
**commented out** in `application.properties`. So as configured, this file is currently inert; it's kept as a
ready-to-enable seed script. Worth noticing, not "fixing" unless you intentionally want seed data.

## Interview questions
1. **Why is `ddl-auto=create` dangerous in production?** It destroys and rebuilds the schema on every
   restart — any data present is lost, and it gives no forward migration history.
2. **What does `spring.jpa.defer-datasource-initialization=true` actually control?** It reorders Hibernate
   schema generation to run *before* `data.sql`/`schema.sql`, instead of Spring Boot's default (SQL scripts
   run before Hibernate creates tables), which would otherwise fail with "relation does not exist."
3. **Why put the JWT secret in `application.properties` instead of hardcoding it in `AuthUtil`?**
   Externalizing config lets the same code run with different secrets per environment without recompiling,
   and (in a real deployment) lets the value be injected from a vault instead of source control.

## Summary
This package holds the knobs that decide *how* the JPA/Hibernate/Security machinery documented elsewhere in
this project actually behaves at runtime — read it alongside `src/MASTER_NOTES.md`'s "Configuration flow"
section.

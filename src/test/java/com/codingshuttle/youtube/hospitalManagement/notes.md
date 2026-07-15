# `test/` — Manual JPA Exploration Harnesses

## Purpose
These are **not** production-style unit tests with assertions — they're `@SpringBootTest` classes used to
manually exercise real services/repositories against a real database while learning how JPA/Hibernate
behaves, then read the printed output and the `show-sql` console log. That's a legitimate and common way to
learn JPA (you need to *see* the generated SQL and returned objects to build intuition for lazy loading,
dirty checking, cascades), even though it isn't how you'd structure a test suite meant to guard against
regressions in a real product.

## Files

- **`HospitalManagementApplicationTests`** — the default Spring Boot smoke test (`contextLoads()`). Its only
  job is to fail fast if the `ApplicationContext` can't start (missing bean, bad config, unreachable
  datasource) — deliberately assertion-free.
- **`InsuranceTests`** — drives `InsuranceService.assignInsuranceToPatient` /
  `disaccociateInsuranceFromPatient` end-to-end and prints the resulting `Patient`. Because
  `Patient.insurance` is `cascade=ALL, orphanRemoval=true`, watch the SQL log here: assigning insurance
  issues an INSERT for `Insurance` plus an UPDATE on `patient.patient_insurance_id`; disassociating issues a
  DELETE on the `insurance` row purely from setting the reference to `null` — no explicit
  `insuranceRepository.delete(...)` call anywhere.
- **`PatientTests`** — exercises `PatientRepository` query methods (`findAllPatientWithAppointment`,
  `findAllPatients` with `PageRequest`/`Sort`). Many lines are commented out — these are prior experiments
  (calling `findByBirthDateOrEmail`, `countEachBloodGroupType`, `updateNameWithId`, etc.) kept in place so
  you can uncomment one at a time and observe the resulting SQL. Treat the commented code as a menu of
  experiments to try, not dead code to delete.

## Why these aren't "real" tests
- No `@Test` method has an `assertEquals`/`assertThat` — they rely on the developer reading console output.
- They hit a real, running PostgreSQL instance (no `@DataJpaTest`, no H2/Testcontainers, no transactional
  rollback isolation) — running them repeatedly can accumulate data.
- They're a fine tool for *this* purpose (building JPA intuition) — for a production codebase you'd want
  `@DataJpaTest` with an isolated test database/Testcontainers, explicit assertions, and no reliance on
  printed output.

## Interview questions
1. **What does `@SpringBootTest` do differently from `@DataJpaTest`?** `@SpringBootTest` boots the entire
   application context (all beans, real datasource); `@DataJpaTest` boots only the JPA-related slice,
   auto-configures an in-memory database by default, and wraps each test in a transaction that rolls back —
   much faster and isolated, appropriate for exactly this kind of repository experimentation.
2. **Why might `findAllPatientWithAppointment`'s printed list look different across runs?** Because
   `Patient.appointments` is `fetch=EAGER`, cascades, and `orphanRemoval=true` — inserting/deleting via other
   tests in the same database changes what's there; there's no test isolation here.

## Summary
This directory is a scratchpad for observing Hibernate's behavior directly, not a regression-test suite —
read it alongside the relevant `NOTES/` concept files (Dirty Checking, Cascade & orphanRemoval,
Pagination & Sorting) to connect what you see printed here to the underlying JPA mechanism.

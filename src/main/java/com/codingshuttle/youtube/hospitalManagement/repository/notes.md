# `repository/` package notes

## Purpose

This package holds Spring Data JPA repository interfaces — one per aggregate root entity
(`AppointmentRepository`, `DoctorRepository`, `PatientRepository`, `UserRepository`,
`InsuranceRepository`, `DepartmentRepository`). Each extends `JpaRepository<Entity, Long>`,
which gives free CRUD (`save`, `findById`, `findAll`, `delete`, ...) plus pagination/sorting
support, without writing any implementation — Spring Data generates a runtime proxy backed by
Hibernate.

## Responsibilities

- Define the data-access contract for one entity type: what queries the rest of the app is
  allowed to run against that table.
- Choose the right query mechanism per method: derived (name-parsed), JPQL (`@Query`), or
  native SQL (`@Query(nativeQuery = true)`).
- Nothing else — no business logic, no transaction orchestration beyond what a single query
  needs (`@Transactional` only appears here on the one bulk-update method that requires it).

## Interaction with other packages

- **`entity/`**: every repository is generic over one entity type and only knows about JPA
  entities, never DTOs (except as a projection *return* type — see
  `PatientRepository#countEachBloodGroupType`, which returns
  `dto.BloodGroupCountResponseEntity`).
- **`service/`**: services (`PatientService`, `DoctorService`, `AppointmentService`,
  `InsuranceService`, `AuthService`) are the only callers of these repositories. Services own
  `@Transactional` boundaries and dirty-checking flows (e.g.
  `AppointmentService.reAssignAppointmentToAnotherDoctor` mutates a managed `Appointment` and
  relies on Hibernate's flush-time dirty checking rather than calling a repository save).
- **`security/`**: `UserRepository.findByUsername` is called directly from `JwtAuthFilter` on
  every authenticated request (to reload the `User`/principal from the JWT's username) and from
  `CustomUserDetailsService` during password login.

## PatientRepository query methods — mechanism and purpose

| Method | Mechanism | What it demonstrates |
|---|---|---|
| `findByName` | Derived (method-name parsed) | Simplest case: one field, no `@Query` needed |
| `findByBirthDateOrEmail` | Derived | Boolean `Or` combinator expressed in the method name |
| `findByBirthDateBetween` | Derived | `Between` keyword → SQL `BETWEEN` |
| `findByNameContainingOrderByIdDesc` | Derived | `Containing` (LIKE) + `OrderBy...Desc` chained in one name — shows the naming convention's upper complexity limit |
| `findByBloodGroup` | JPQL (`@Query`, positional `?1` binding) | Explicit JPQL for an enum-typed filter; positional parameter binding |
| `findByBornAfterDate` | JPQL (`@Query`, named `:birthDate` binding) | Named parameter binding — preferred over positional for readability/reorder-safety |
| `countEachBloodGroupType` | JPQL constructor-expression / DTO projection | `SELECT new <FQCN>(...)` builds `BloodGroupCountResponseEntity` instances directly from a grouped aggregate query, skipping full entity hydration |
| `findAllPatients` | Native SQL + `Pageable`/`Page` | Raw SQL (`select * from patient`) with Spring Data still applying LIMIT/OFFSET and a count query because the return type is `Page` |
| `updateNameWithId` | `@Modifying` + `@Transactional` JPQL UPDATE | Bulk DML that bypasses the persistence context/dirty-checking entirely |
| `findAllPatientWithAppointment` | JPQL `JOIN FETCH` | Avoids N+1 by fetching `Patient` + `appointments` in one query |

## Common mistakes (and where this codebase shows them deliberately)

- **Forgetting `@Modifying` on a DML `@Query`**: without it, Spring Data assumes the query
  returns entities and throws at runtime. `@Modifying` alone isn't enough either —
  `updateNameWithId` also needs `@Transactional` because bulk updates must run inside a
  transaction; Spring won't open one implicitly for a bare repository method.
- **Bulk updates and stale managed entities**: `@Modifying` queries write straight to the DB and
  skip the persistence context. If a `Patient` with the same id is already loaded/managed in the
  current transaction, that in-memory copy will NOT reflect the bulk update until it's
  reloaded/refreshed — a classic "why didn't my change show up" bug.
- **N+1 without `JOIN FETCH`**: calling `findAll()` on patients and then touching a LAZY
  association per row triggers one extra query per row. `findAllPatientWithAppointment` fixes
  this for `appointments` in one shot; the commented-out double-`JOIN FETCH` variant
  (`... LEFT JOIN FETCH a.doctor`) shows how you'd extend it further, but stacking fetch-joins on
  multiple collections can trigger Hibernate's `MultipleBagFetchException` or multiply row counts
  — not a free lunch.
- **Constructor-expression projection mismatches**: `SELECT new
  com.codingshuttle...BloodGroupCountResponseEntity(p.bloodGroup, Count(p))` is a plain string —
  it is NOT compile-checked against the DTO. Renaming the DTO's package/class, reordering its
  `@AllArgsConstructor` parameters, or changing a field's type will compile fine and fail only at
  query-execution time.
- **Confusing positional (`?1`) and named (`:param`) bindings**: both work, but positional
  bindings are fragile under refactors that reorder parameters; this repo shows both styles
  side by side (`findByBloodGroup` vs `findByBornAfterDate`).

## Best practices illustrated here

- Default to derived query methods for simple, single/two-field filters — they're generated at
  startup, need no JPQL string, and stay type-safe.
- Escalate to JPQL once the naming convention would become unreadable, or you need aggregates/
  projections that don't map onto a single entity field.
- Escalate to native SQL only when JPQL genuinely can't express what you need (DB-specific
  functions, etc.) — you lose the entity-name abstraction and portability.
- Use DTO projections (constructor expressions) instead of returning `List<Object[]>` when you
  need aggregate results — better type safety and readability, at the cost of the projection
  class having to stay in lockstep with the query string.
- Always pair `@Modifying` with `@Transactional`, and be conscious of persistence-context
  staleness after a bulk update.
- Reach for `JOIN FETCH` deliberately (not by default) — it's a targeted fix for a known N+1,
  not something to sprinkle on every query.

## Interview questions

**Q: What's the difference between a derived query method and a `@Query`-annotated one?**
A: A derived method (e.g. `findByBirthDateBetween`) is parsed from its method name at
application startup into a query — no JPQL string to write, but limited to what the naming
grammar supports. `@Query` methods (e.g. `findByBloodGroup`) give you an explicit JPQL or native
SQL string, needed once the query logic outgrows what a method name can express.

**Q: Why does `updateNameWithId` need both `@Modifying` and `@Transactional`?**
A: `@Modifying` tells Spring Data this `@Query` is a DML statement (UPDATE/DELETE), not a
SELECT, so it should be executed via `executeUpdate()` rather than a result-set read.
`@Transactional` is required because that kind of bulk write must happen inside a transaction —
Spring Data won't silently wrap it for you. Together they let a single UPDATE statement run
directly against the database, bypassing the persistence context and its dirty-checking.

**Q: What problem does `findAllPatientWithAppointment`'s `LEFT JOIN FETCH` solve?**
A: Without it, loading a list of patients and then accessing each one's `appointments`
collection would run one extra SELECT per patient (N+1 queries). `LEFT JOIN FETCH p.appointments`
tells Hibernate to pull the association in the same single query via a SQL JOIN. `LEFT` (rather
than inner join) ensures patients with zero appointments are still returned.

**Q: How does `countEachBloodGroupType` return a DTO instead of a `Patient` or `Object[]`?**
A: It uses a JPQL constructor expression — `SELECT new
com.codingshuttle.youtube.hospitalManagement.dto.BloodGroupCountResponseEntity(p.bloodGroup,
Count(p))` — which tells Hibernate to call that DTO's matching constructor with each result row's
columns, building DTO instances directly instead of full `Patient` entities or raw
`Object[]` rows. It requires the DTO's fully-qualified name and a constructor whose parameter
types/order match the selected expressions exactly.

**Q: Why is `findAllPatients` a native query but still supports pagination?**
A: `nativeQuery = true` just means the string is raw SQL run directly against the table instead
of JPQL against the entity model. Spring Data still recognizes the `Pageable` parameter and
`Page<Patient>` return type and appends the DB-appropriate LIMIT/OFFSET (plus a separate COUNT
query) around the native SQL — pagination support is orthogonal to whether the query is JPQL or
native.

## Summary

Every repository in this package is a thin `JpaRepository` extension; most (Appointment, Doctor,
Insurance, Department) need nothing beyond inherited CRUD. `UserRepository` adds two small
derived finders used by the security layer. `PatientRepository` is the teaching centerpiece: it
walks through the full spectrum of Spring Data query mechanisms — derived methods, JPQL with both
positional and named binding, a DTO constructor-expression projection, a paginated native query,
a `@Modifying`+`@Transactional` bulk update, and a `JOIN FETCH` to avoid N+1 — each chosen for a
specific reason rather than arbitrarily.

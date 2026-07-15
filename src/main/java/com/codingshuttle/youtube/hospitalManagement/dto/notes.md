# `dto/` package notes

## Purpose

This package defines the API-boundary shapes — request bodies clients send in, and response
bodies the app sends out. None of these classes are `@Entity`s; they are plain Lombok-annotated
POJOs whose only job is to define the wire contract independently of the JPA entity model.

## Why DTOs instead of returning entities directly

- **Lazy-loading/serialization hazards**: entities like `Patient` and `Doctor` carry JPA
  associations (`Patient.appointments` is `EAGER`, `Doctor.appointments` is LAZY,
  `Patient.insurance` is cascade-`ALL`/`orphanRemoval`). Serializing an entity straight to JSON
  either drags along data the client didn't ask for (EAGER collections) or throws
  `LazyInitializationException` once the Hibernate session/persistence context is closed (LAZY
  proxies accessed outside a transaction). DTOs are flat, serialization-safe snapshots.
- **Hiding internal fields**: `User` holds `password` and security-relevant fields; `Doctor`/
  `Patient` share a primary key with `User` via `@MapsId`. None of that should leak to a client —
  `DoctorResponseDto`/`PatientResponseDto` expose only what's meant to be public.
- **Controlling the wire contract independently of persistence changes**: entities can be
  restructured (add a column, change a cascade, rename a relation) without necessarily changing
  what the API returns, and vice versa — the DTO is the stable contract layer.

## Responsibilities

- Request DTOs (`LoginRequestDto`, `SignUpRequestDto`, `CreateAppointmentRequestDto`,
  `OnboardDoctorRequestDto`) define exactly what a client is allowed to submit — plain ids
  (`doctorId`, `patientId`, `userId`) rather than entity references, since resolving an id into a
  managed entity is the service layer's job, not the request parser's.
- Response DTOs (`LoginResponseDto`, `SignupResponseDto`, `DoctorResponseDto`,
  `PatientResponseDto`, `AppointmentResponseDto`) define exactly what a client receives back.
- `BloodGroupCountResponseEntity` is a special case: despite the "Entity" in its name it is NOT
  a JPA `@Entity` — it exists purely as the target type of a JPQL constructor-expression
  projection in `PatientRepository#countEachBloodGroupType` (`SELECT new
  ...BloodGroupCountResponseEntity(p.bloodGroup, Count(p))`). Its `@AllArgsConstructor`
  parameter order/types and fully-qualified class name are load-bearing for that JPQL string —
  changing them without updating the query breaks it at query-execution time, not compile time.

## Interaction with other packages

- **`entity/`**: DTOs never extend or reference entities directly (aside from reusing shared
  `enum` types like `BloodGroupType`, `RoleType` for field typing). The mapping between entity and
  DTO happens in the service layer.
- **`service/`**: services use the `ModelMapper` bean (defined in `AppConfig`) —
  `modelMapper.map(entity, XDto.class)` — to convert entities into response DTOs by convention
  (matching field names/types via reflection), avoiding hand-written mapper boilerplate. Request
  DTOs are read by controllers and passed into services, which pull out the plain fields
  (ids, strings) needed to look up/construct entities.
- **`repository/`**: the only repository-level dependency is `BloodGroupCountResponseEntity`,
  which `PatientRepository` returns directly from a JPQL projection query instead of a full
  `Patient` entity or `Object[]`.
- **`controller/`**: controllers accept request DTOs as `@RequestBody` and return response DTOs
  (or types wrapping them) — this is the actual point where the "API boundary" is enforced.

## Patterns used in this project

- **Lombok `@Data` + `@AllArgsConstructor` + `@NoArgsConstructor`**: near-universal pattern in
  this package — `@Data` generates getters/setters/equals/hashCode/toString, the two constructors
  support both no-arg (needed by frameworks like Jackson/ModelMapper that construct-then-populate)
  and all-arg (needed for constructor-expression projections like
  `BloodGroupCountResponseEntity`, and for convenient object construction in code/tests).
- **`SignupResponseDto` uses `@Builder` in addition** — a minor stylistic variation letting
  callers build it fluently instead of via constructor/setters.
- **Deliberately asymmetric contracts**: `SignupResponseDto` returns only `id`+`username` (no
  JWT — signup and login are separate steps), while `LoginResponseDto` returns `jwt`+`userId` and
  is shared by BOTH plain login and OAuth2 login (`OAuth2SuccessHandler` builds the same DTO) so
  clients don't need to know which auth flow was used.
- **Commented-out field as a teaching artifact**: `AppointmentResponseDto.patient` is commented
  out on purpose (left in, not deleted) — the DTO is used from doctor-facing flows where the
  patient is already contextual, so it was intentionally left out of the response shape.

## Common mistakes

- Returning JPA entities directly from controllers instead of mapping to a DTO — risks
  `LazyInitializationException` (once outside the persistence context) or leaking fields
  (password, internal ids/associations) that were never meant to be public.
- Editing `BloodGroupCountResponseEntity`'s constructor shape (field order/types) without
  updating the matching `SELECT new ...(...)` JPQL string in `PatientRepository` — this compiles
  fine and fails only when the query actually runs.
- Forgetting `@NoArgsConstructor` on a DTO that Jackson (or another reflection-based framework)
  needs to deserialize — breaks JSON→object binding for request DTOs.
- Assuming `ModelMapper`'s convention-based field matching will always "just work" — a field name
  or type mismatch between entity and DTO silently maps to `null`/default rather than failing
  loudly, which is a real debuggability cost of this convenience.

## Best practices illustrated here

- Keep request DTOs minimal and primitive-typed (ids, strings, enums) — let the service layer own
  resolving those into entities.
- Keep response DTOs flat and serialization-safe — no JPA associations, no lazy proxies.
- Share a response DTO across multiple flows when the contract is genuinely the same
  (`LoginResponseDto` for both password and OAuth2 login) rather than duplicating near-identical
  shapes.
- When a DTO exists solely to be a JPQL projection target, keep its constructor exactly aligned
  with the query and document that coupling clearly (see `BloodGroupCountResponseEntity`).

## Interview questions

**Q: Why not just return the `Patient`/`Doctor` entity directly from a REST controller?**
A: Entities carry JPA-specific concerns (lazy proxies, EAGER collections, internal FK/association
structure, security-sensitive fields on `User`) that shouldn't be part of the wire contract.
`Patient.appointments` is EAGER and would be serialized in full on every fetch;
`Doctor.appointments` is LAZY and would throw if accessed outside a transaction. DTOs
(`PatientResponseDto`, `DoctorResponseDto`) decouple the API shape from these persistence
details.

**Q: What is `BloodGroupCountResponseEntity` and why is it named "Entity" despite not being a
JPA entity?**
A: It's a plain DTO used as the projection target of a JPQL constructor expression in
`PatientRepository#countEachBloodGroupType`. Hibernate builds instances of it directly from the
grouped query results (`p.bloodGroup`, `Count(p)`) via its `@AllArgsConstructor`. The "Entity"
in the name is misleading/legacy naming — it's not annotated `@Entity` and has no table.

**Q: How does `ModelMapper` get used with these DTOs, and what's a downside of that approach?**
A: Services call `modelMapper.map(entity, XDto.class)`, which reflectively matches fields by name
and type between the entity and DTO. It saves writing manual mapper code, but the matching isn't
compile-checked — a renamed or mismatched field silently maps to a default value instead of
failing the build, making mapping bugs harder to catch than with hand-written mappers.

**Q: Why does `LoginResponseDto` get reused by both the plain login flow and the OAuth2 flow?**
A: So that regardless of how a user authenticated (username/password vs Google/GitHub OAuth2),
the client receives an identical response shape (`jwt` + `userId`) and downstream client code
doesn't need separate handling per auth method — `OAuth2SuccessHandler` builds this same DTO
after a successful OAuth2 login.

**Q: What would happen if you reordered the fields in `BloodGroupCountResponseEntity`'s
constructor without touching `PatientRepository`?**
A: Nothing at compile time — the JPQL `SELECT new ...(...)` string isn't type-checked against the
DTO by the compiler. It would only fail (or silently misassign values, e.g. count landing in the
blood-group field) at query-execution time when Hibernate tries to invoke the constructor with
mismatched argument types/order.

## Summary

The `dto/` package is the API contract layer: request DTOs define what clients may submit
(kept minimal — ids and primitives), response DTOs define what clients receive back (kept flat
and free of JPA associations), and one special case (`BloodGroupCountResponseEntity`) doubles as
a JPQL projection target whose constructor shape is load-bearing for a query in
`PatientRepository`. The consistent use of Lombok `@Data`/`@AllArgsConstructor`/
`@NoArgsConstructor` plus `ModelMapper`-based conversion in the service layer keeps this
boundary thin and mechanical, at the cost of some compile-time safety that's worth knowing about.

# `controller/` package notes

## Purpose

This package is the HTTP boundary of the application: `@RestController` classes that map incoming
requests to service calls and wrap the results in `ResponseEntity`. Every controller in this
project is intentionally thin — no business logic, no direct repository access, no entity
manipulation.

## Responsibilities

- `AuthController` (`/auth/**`, public) — login and signup, delegates to `security/AuthService`.
- `HospitalController` (`/public/**`, public) — public doctor directory, delegates to
  `DoctorService.getAllDoctors()`.
- `PatientController` (`/patients/**`, authenticated) — create appointments and fetch the caller's
  own profile, delegates to `AppointmentService`/`PatientService`.
- `DoctorController` (`/doctors/**`, DOCTOR or ADMIN role) — list the authenticated doctor's
  appointments, delegates to `AppointmentService.getAllAppointmentsOfDoctor`.
- `AdminController` (`/admin/**`, ADMIN role) — list all patients (paginated), onboard new doctors,
  delegates to `PatientService`/`DoctorService`.

## Package interaction

```
HTTP client
     |
     v
controller/*Controller     (THIS PACKAGE: @RequestMapping, @RequestBody/@ResponseEntity,
     |                       reads SecurityContextHolder when it needs the caller's identity,
     |                       zero business logic)
     v
service/*Service            (@Transactional boundaries, business rules, method security,
     |                       entity <-> DTO mapping)
     v
repository/*Repository      (Spring Data JPA queries)
     |
     v
Hibernate / PostgreSQL
```

Concretely: `DoctorController.getAllAppointmentsOfDoctor()` reads the `User` principal straight out
of `SecurityContextHolder` (since `User` implements `UserDetails` directly and IS the principal —
see `security/JwtAuthFilter`), then calls
`appointmentService.getAllAppointmentsOfDoctor(user.getId())`, which internally does its own
`@PreAuthorize` ownership check before touching `DoctorRepository`.

## Important annotations & patterns (with real examples from this project)

### Controllers stay thin, security is layered

URL-level security lives in `WebSecurityConfig` (e.g. `/admin/**` requires `ROLE_ADMIN`,
`/public/**` is open). Controllers do not re-implement or duplicate these checks. Where a decision
needs to inspect method arguments (e.g. "is this doctor reassigning their own appointment?"), that
logic is pushed down into the service layer's `@PreAuthorize`/`@Secured` annotations
(`AppointmentService`), not into the controller. This is why `AdminController` and
`HospitalController` contain literally nothing but a one-line delegate per endpoint.

### DTOs as the request/response contract, never entities

Every `@RequestBody` and `ResponseEntity<...>` type across this package is a DTO
(`CreateAppointmentRequestDto`, `OnboardDoctorRequestDto`, `PatientResponseDto`,
`AppointmentResponseDto`, `LoginRequestDto`/`LoginResponseDto`, `SignUpRequestDto`/
`SignupResponseDto`, `DoctorResponseDto`) — never `Patient`, `Doctor`, `Appointment`, or `User`
directly. This keeps the HTTP contract independent of the JPA entity graph (adding a lazy
association to an entity can't accidentally break JSON serialization of an endpoint) and prevents
clients from supplying entity fields they shouldn't control.

### Reading the principal directly (no extra UserDetails mapping)

`DoctorController.getAllAppointmentsOfDoctor()`:

```java
User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
return ResponseEntity.ok(appointmentService.getAllAppointmentsOfDoctor(user.getId()));
```

Because `User` implements `UserDetails` directly (there's no separate "UserDetails wrapper around a
domain user" layer), the principal object placed into the `SecurityContextHolder` by
`JwtAuthFilter` can be cast straight back to the domain `User` entity. This is what lets the
controller derive `doctorId` from the token instead of trusting a client-supplied value.

## Common mistakes to watch for

- **Hardcoded IDs bypassing the security context** — `PatientController.getPatientProfile()`:

  ```java
  @GetMapping("/profile")
  private ResponseEntity<PatientResponseDto> getPatientProfile() {
      Long patientId = 4L;
      return ResponseEntity.ok(patientService.getPatientById(patientId));
  }
  ```

  This always returns patient id `4`'s profile regardless of who's authenticated, instead of
  deriving the id from `SecurityContextHolder` the way `DoctorController` does. It's a known rough
  edge left in this learning project — a good example of "looks like it works in a demo, breaks
  multi-user correctness immediately."

- **Putting business logic or repository calls in a controller** — none of the controllers here do
  this, and that's deliberate; if you find yourself reaching for a `*Repository` from a controller,
  that logic belongs in a service.

- **Returning entities instead of DTOs** — would leak persistence details and risk
  `LazyInitializationException` during JSON serialization if the HTTP response is written after the
  Hibernate session/transaction has closed.

- **Re-implementing authorization in the controller** — duplicating what `WebSecurityConfig` and
  service-level `@PreAuthorize`/`@Secured` already enforce, which risks the checks drifting out of
  sync with each other.

## Best practices demonstrated here

- One `@RestController` per resource/audience (`AdminController` vs `PatientController` vs
  `DoctorController`) rather than one giant controller, making URL-prefix-based security rules in
  `WebSecurityConfig` simple to write.
- Consistent `ResponseEntity<SomeDto>` return types with explicit HTTP status where it matters
  (`HttpStatus.CREATED` for the two POST-that-creates endpoints: `AdminController.onBoardNewDoctor`
  and `PatientController.createNewAppointment`).
- Deriving the caller's identity from the security principal rather than trusting request
  parameters, when correctly done (`DoctorController`) — contrast with the profile endpoint's
  hardcoded id, which shows what happens when this pattern is skipped.

## Interview questions grounded in this project

**Q1: Why does `PatientController.getPatientProfile()` always return the same patient's data no
matter who's logged in?**
A: It hardcodes `Long patientId = 4L` instead of deriving the id from the authenticated principal.
The fix (not applied here, since this is a documentation-only pass) would mirror
`DoctorController.getAllAppointmentsOfDoctor()`: cast
`SecurityContextHolder.getContext().getAuthentication().getPrincipal()` to `User` and use its
linked patient id.

**Q2: How does `DoctorController` know which doctor is calling, without a `doctorId` path variable?**
A: `User` implements `UserDetails` directly, so the object `JwtAuthFilter` places into the
`SecurityContextHolder` as the authentication's principal IS the domain `User` entity. The
controller casts the principal back to `User` and reads `user.getId()`.

**Q3: Why do these controllers accept and return DTOs instead of JPA entities?**
A: To decouple the HTTP contract from the persistence model. If an entity gained a new lazy
association or a field were renamed for a migration, a DTO-based contract wouldn't automatically
break; returning entities directly also risks trying to serialize an uninitialized lazy proxy after
the Hibernate session has closed, causing a `LazyInitializationException`.

**Q4: `AdminController`'s `/admin/**` routes require `ROLE_ADMIN` — where is that enforced, and why
isn't there an explicit check inside `AdminController` itself?**
A: It's enforced at the URL level in `WebSecurityConfig` via Spring Security's HTTP request
matchers, before the request even reaches the controller. Keeping that check out of the controller
avoids duplicating security logic in two places that could drift out of sync.

**Q5: Both `AdminController.onBoardNewDoctor` and `PatientController.createNewAppointment` return
`HttpStatus.CREATED`. What's the reasoning, and where does the actual creation happen?**
A: `201 Created` is the correct REST status for an endpoint that creates a new resource (a Doctor
record, an Appointment record). The controllers themselves don't create anything — they set the
status and delegate entirely to `DoctorService.onBoardNewDoctor` / `AppointmentService.createNewAppointment`,
which do the actual entity creation and persistence inside a `@Transactional` boundary.

## Summary

The `controller/` package is a thin HTTP adapter: it parses requests into DTOs, optionally reads the
authenticated `User` principal out of `SecurityContextHolder`, delegates every real decision to the
`service/` package, and wraps results in `ResponseEntity` with an appropriate status code. Security
is layered — coarse URL-prefix rules in `WebSecurityConfig`, finer per-argument rules in the service
layer — and controllers never bypass that layering by embedding their own authorization or
persistence logic. The one notable exception worth studying is `PatientController.getPatientProfile()`'s
hardcoded `patientId = 4L`, a realistic example of an identity-derivation shortcut that works in a
single-user demo but is wrong for a multi-user system.

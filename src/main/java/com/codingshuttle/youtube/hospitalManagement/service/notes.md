# `service/` package notes

## Purpose

This package holds the application's business logic layer. It sits between the `controller/`
package (HTTP concerns) and the `repository/` package (persistence access), and is where:

- Entities are loaded/mutated inside managed transactions.
- Entity <-> DTO conversion happens (via the shared `ModelMapper` bean from `AppConfig`).
- Method-level security (`@Secured`, `@PreAuthorize`) is enforced, complementing the URL-level
  rules in `WebSecurityConfig`.

## Responsibilities

- `AppointmentService` — create appointments, reassign a doctor on an existing appointment, list a
  doctor's appointments. The canonical example of Hibernate dirty checking in this codebase.
- `DoctorService` — list doctors, onboard a new doctor (elevates an existing `User` to `Doctor` +
  adds the `DOCTOR` role).
- `PatientService` — fetch a single patient, paginated listing of all patients (native SQL query).
- `InsuranceService` — assign/disassociate insurance to/from a patient; the canonical example of
  `orphanRemoval`-triggered deletes in this codebase.

## Package interaction

```
HTTP request
     |
     v
controller/*Controller   (thin: binds request, delegates, wraps ResponseEntity)
     |
     v
service/*Service         (THIS PACKAGE: @Transactional boundaries, business rules,
     |                     method security, entity<->DTO mapping via ModelMapper)
     v
repository/*Repository   (Spring Data JPA: derived queries, @Query JPQL/native, paging)
     |
     v
Hibernate / PostgreSQL
```

Concretely: `PatientController.createNewAppointment` -> `AppointmentService.createNewAppointment`
-> `PatientRepository.findById` + `DoctorRepository.findById` + `AppointmentRepository.save` ->
Hibernate issues INSERT/UPDATE SQL against Postgres.

## Important annotations & patterns (with real examples from this project)

### `@Transactional` + dirty checking

`AppointmentService.reAssignAppointmentToAnotherDoctor`:

```java
@Transactional
@PreAuthorize("hasAuthority('appointment:write') or #doctorId == authentication.principal.id")
public Appointment reAssignAppointmentToAnotherDoctor(Long appointmentId, Long doctorId) {
    Appointment appointment = appointmentRepository.findById(appointmentId).orElseThrow();
    Doctor doctor = doctorRepository.findById(doctorId).orElseThrow();

    appointment.setDoctor(doctor); // no explicit save() - yet this IS persisted
    doctor.getAppointments().add(appointment);

    return appointment;
}
```

There is **no `appointmentRepository.save(appointment)` call**, and the change is still written to
the database. Because the method is `@Transactional`, `appointment` is a managed entity for the
whole method (it came from `findById`, not a detached/new instance). Hibernate keeps a snapshot of
its loaded state; when the transaction commits (flushes the persistence context), it diffs the
current entity state against the snapshot, sees `doctor` changed, and issues the `UPDATE` itself.
This only works because the entity stays managed and attached — if the method weren't
`@Transactional`, the entity would become detached right after `findById` returns (session closes),
and mutating it afterward would silently do nothing to the database.

### Bidirectional consistency (in-memory only, no persistence effect)

`AppointmentService.createNewAppointment`:

```java
appointment.setPatient(patient);
appointment.setDoctor(doctor);
patient.getAppointments().add(appointment); // to maintain consistency
```

`Appointment` is the FK-owning side of both relationships (it holds `patient_id` and `doctor_id`
columns), so `appointment.setPatient(...)`/`setDoctor(...)` are what Hibernate actually persists.
`patient.getAppointments().add(appointment)` (and the equivalent
`doctor.getAppointments().add(appointment)` in `reAssignAppointmentToAnotherDoctor`) exist purely so
the in-memory object graph is self-consistent — if something later reads
`patient.getAppointments()` without re-querying the DB, it sees the correct data. Forgetting these
lines would not corrupt the database, but could cause stale/incorrect in-memory collections within
the same persistence context.

### Cascade + `orphanRemoval`

`InsuranceService.disaccociateInsuranceFromPatient`:

```java
@Transactional
public Patient disaccociateInsuranceFromPatient(Long patientId) {
    Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new EntityNotFoundException("Patient not found with id: " + patientId));

    patient.setInsurance(null);
    return patient;
}
```

`Patient.insurance` is declared `@OneToOne(cascade=CascadeType.ALL, orphanRemoval=true)`. Setting it
to `null` on a managed `Patient` doesn't just null the FK column — `orphanRemoval=true` tells
Hibernate that an `Insurance` no longer referenced by its owning `Patient` is orphaned and should be
deleted outright. So this one line triggers a `DELETE` on the `Insurance` row at flush time. Without
`orphanRemoval`, the same code would just null the FK and leave a dangling `Insurance` row.

### `ModelMapper` at the service boundary

Nearly every read-returning method in this package ends with something like:

```java
return modelMapper.map(patient, PatientResponseDto.class);
```

Entities are never returned directly from a service to a controller (with the notable exception of
`AppointmentService.reAssignAppointmentToAnotherDoctor`, which returns the entity `Appointment`
itself — an inconsistency worth noticing, not a pattern to copy). Converting to DTOs at this
boundary: keeps the HTTP contract decoupled from the JPA entity graph (so entity changes don't
silently break clients), avoids accidentally serializing lazy proxies straight into JSON, and hides
internal-only fields.

### Method-level security alongside URL-level security

`WebSecurityConfig` gates whole URL prefixes (e.g. `/admin/**` needs `ROLE_ADMIN`), but some
authorization rules need to inspect the actual arguments of a call — something a URL pattern can't
express. `AppointmentService.reAssignAppointmentToAnotherDoctor`:

```java
@PreAuthorize("hasAuthority('appointment:write') or #doctorId == authentication.principal.id")
```

This allows a caller with the coarse `appointment:write` authority (e.g. an admin) OR a doctor
reassigning to *themselves* (`#doctorId == authentication.principal.id`, comparing the method
argument to the JWT principal's id, since `User` is directly the security principal). `@Secured`
(used on `createNewAppointment`) is the older, simpler annotation — role-name-only, no SpEL, no
access to method arguments — appropriate when the rule really is just "must have this role."

## Common mistakes to watch for

- **Missing `@Transactional` on a method that relies on dirty checking** — the mutation is silently
  lost because the entity detaches as soon as the repository call returns.
- **Forgetting the bidirectional-consistency line** — doesn't break the DB, but can cause a
  read-after-write within the same transaction/persistence context to look stale.
- **Returning an entity instead of a DTO** (see `reAssignAppointmentToAnotherDoctor`) — couples the
  API response shape to the JPA model and risks lazy-loading exceptions during JSON serialization.
- **Assuming `save()` is always required** — in this codebase it's only needed for *new* entities or
  entities loaded outside the current transaction; updates to already-managed entities happen via
  dirty checking.
- **Hardcoded IDs slipping into "service" territory from controllers** — see
  `PatientController.getPatientProfile()`'s `patientId = 4L`; the service itself is fine and generic
  (`getPatientById(Long patientId)`), the bug is entirely in what the controller passes in.

## Best practices demonstrated here

- Keep `@Transactional` scoped to the method that needs atomic, multi-entity mutation — not sprayed
  across every method.
- Do all entity mutation through managed entities inside a transaction; let Hibernate's dirty
  checking do the UPDATE instead of manually re-saving.
- Map to DTOs before returning from a service method, so the transaction/session is still open when
  ModelMapper's reflection (potentially) touches lazy fields.
- Express fine-grained, per-argument authorization with `@PreAuthorize` SpEL rather than trying to
  encode it into URL patterns.

## Interview questions grounded in this project

**Q1: In `AppointmentService.reAssignAppointmentToAnotherDoctor`, there's no `save()` call, yet the
new doctor assignment is persisted. How?**
A: The `Appointment` is loaded via `appointmentRepository.findById(...)` inside a `@Transactional`
method, so it stays a managed entity attached to the persistence context. Hibernate tracks its
loaded-state snapshot and, at flush/commit time, compares the current field values against that
snapshot. Since `doctor` differs, Hibernate generates an `UPDATE` automatically — this is dirty
checking, and it only works because the entity never detached.

**Q2: What does `orphanRemoval=true` do in `Patient.insurance`, and how does
`InsuranceService.disaccociateInsuranceFromPatient` rely on it?**
A: `orphanRemoval=true` tells Hibernate that if the parent (`Patient`) stops referencing the child
(`Insurance`) — e.g. via `patient.setInsurance(null)` — the now-unreferenced child should be deleted,
not just have its FK nulled. `disaccociateInsuranceFromPatient` relies on exactly this: it sets the
reference to null and returns, with no explicit delete call, yet the `Insurance` row is removed.

**Q3: Why do `createNewAppointment` and `reAssignAppointmentToAnotherDoctor` update both
`appointment.setDoctor(doctor)` AND `doctor.getAppointments().add(appointment)`?**
A: Because `Appointment` is the FK-owning side, only `setDoctor`/`setPatient` affect what's written
to the database. The collection-side update (`doctor.getAppointments().add(...)`) has zero effect on
persistence — it exists solely so the in-memory Java object graph stays consistent if something
reads that collection again within the same persistence context without a fresh DB query.

**Q4: Why does `AppointmentService` use both `@Secured` and `@PreAuthorize` on different methods
instead of picking one?**
A: `@Secured` only supports simple role-name checks with no access to method arguments or SpEL —
sufficient for `createNewAppointment`, where the rule is simply "must be a PATIENT." `@PreAuthorize`
supports full SpEL expressions, which `reAssignAppointmentToAnotherDoctor` needs to compare the
`#doctorId` argument against `authentication.principal.id` for a self-service ownership check that
a role name alone can't express.

**Q5: Why isn't `getAllDoctors()` in `DoctorService` marked `@Transactional`?**
A: It's a single read (`findAll()`) followed immediately by mapping to DTOs, with no multi-step
mutation requiring atomicity. Spring Data repository methods run in their own transaction by
default when called directly, and the DTO mapping happens right after, so there's no window where an
extra `@Transactional` would change behavior here.

## Summary

The `service/` package is where this project's core JPA/Hibernate teaching moments live: dirty
checking without explicit `save()`, cascade + `orphanRemoval` triggering deletes, the
owning-side-vs-inverse-side distinction driving why bidirectional setters are needed for in-memory
consistency only, and layered security combining role-based `@Secured`/`@PreAuthorize` with
SpEL-driven self-service checks. Controllers stay deliberately thin so all of this logic — and the
entity/DTO boundary — is concentrated here.

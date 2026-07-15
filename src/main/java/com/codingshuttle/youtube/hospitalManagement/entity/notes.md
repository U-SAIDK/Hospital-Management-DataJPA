# `entity/` package notes

## Purpose

This package holds the JPA domain model for the hospital management system: `User`, `Doctor`,
`Patient`, `Appointment`, `Department`, `Insurance`, plus the plain enums in `entity/type/`
(`AuthProviderType`, `BloodGroupType`, `RoleType`, `PermissionType`). These classes are the single
source of truth for both the database schema (Hibernate generates DDL from them —
`spring.jpa.hibernate.ddl-auto=create`) and the object graph the rest of the app operates on.

A notable design choice: `User` is not just a persistence class, it directly `implements
UserDetails`. There is no separate "security user" class — the entity IS the Spring Security
principal.

## Responsibilities

- Define table/column mapping, constraints (unique, nullable, length) and indexes.
- Define relationships between domain concepts (who owns which foreign key, cascade behavior,
  fetch strategy).
- For `User`, also compute Spring Security `GrantedAuthority`s (`getAuthorities()`), bridging the
  persistence model into the security model.

## How it interacts with other packages

- **repository/**: Spring Data repositories (`PatientRepository`, `DoctorRepository`,
  `UserRepository`, etc.) are typed directly on these entities — derived query methods, JPQL, and
  native queries all read/write these classes.
- **service/**: Services (`AppointmentService`, `PatientService`, `InsuranceService`, `AuthService`,
  ...) load managed entity instances from repositories, mutate them, and rely on JPA's dirty
  checking / cascade rules defined here to persist changes correctly.
- **security/**: `JwtAuthFilter` loads a `User` via `UserRepository.findByUsername` and puts it
  straight into the `SecurityContextHolder` as the authentication principal. `RolePermissionMapping`
  is consulted by `User.getAuthorities()` to expand `RoleType` into `PermissionType` authorities.
- **dto/ + config (ModelMapper)**: entities are mapped to/from DTOs at the service boundary via the
  `ModelMapper` bean defined in `config/AppConfig`.

## Entity relationship diagram

```
                    ┌───────────────┐
                    │     User      │  (implements UserDetails; app_user table)
                    │  id (IDENTITY)│
                    │  username     │
                    │  roles (EAGER)│
                    └───────┬───────┘
                 shared PK  │  shared PK
            ┌───────────────┴───────────────┐
            │ @MapsId                       │ @MapsId
            ▼                               ▼
     ┌─────────────┐                 ┌─────────────┐
     │   Doctor    │                 │   Patient   │
     │ id = user.id│                 │ id = user.id│
     └──────┬──────┘                 └──────┬──────┘
            │ M:N (inverse)                 │
            │ mappedBy="doctors"             │ 1:1 (owning, cascade ALL,
            │                                │      orphanRemoval)
            ▼                                ▼
     ┌─────────────┐                 ┌─────────────┐
     │ Department  │                 │  Insurance  │
     │ (owning M:N,│                 │ (inverse    │
     │ @JoinTable  │                 │  mappedBy=  │
     │ my_dpt_     │                 │  "insurance")
     │ doctors)    │                 └─────────────┘
     └─────────────┘
     Department --(1:1 unidirectional)--> Doctor  (headDoctor)

     Doctor 1───M Appointment M───1 Patient
       (mappedBy="doctor",           (mappedBy="patient",
        LAZY, no cascade)             cascade=REMOVE, orphanRemoval,
                                       EAGER)
```

Key relationship facts (see inline comments in each entity for the "why"):

- `User`↔`Doctor` and `User`↔`Patient`: shared primary key via `@OneToOne @MapsId` — a `Doctor`
  or `Patient` row's id IS the linked `User`'s id, no separate sequence. A given `User` is either a
  Doctor or a Patient (or neither, e.g. an ADMIN), never both in the current usage pattern.
- `Doctor`↔`Department`: many-to-many. `Department` is the owning side (`@JoinTable
  my_dpt_doctors`); `Doctor.departments` is the inverse (`mappedBy="departments"`).
- `Patient`↔`Insurance`: one-to-one. `Patient` owns the FK (`patient_insurance_id`) and fully
  controls Insurance's lifecycle via `cascade=ALL, orphanRemoval=true`.
- `Patient`/`Doctor`↔`Appointment`: one-to-many. `Appointment` holds both FKs
  (`patient_id`, `doctor_id`). Note the **deliberate fetch asymmetry**: `Patient.appointments` is
  `EAGER` with `cascade=REMOVE, orphanRemoval=true`; `Doctor.appointments` is plain `@OneToMany`
  (defaults to `LAZY`, no cascade) — a side-by-side teaching contrast of the two fetch strategies.

## Important annotations used in this project (with real examples)

- `@Entity` / `@Table(name=..., indexes=..., uniqueConstraints=...)` — e.g. `User`'s `app_user`
  table has a composite index `idx_provider_id_provider_type` on `(providerId, providerType)`,
  used by the OAuth2 login path to find an existing account by provider identity.
- `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` — used on every entity's surrogate key
  except where `@MapsId` overrides it (Doctor, Patient).
- `@MapsId` — shared-PK pattern on `Doctor.user` and `Patient.user`.
- `@ElementCollection(fetch = FetchType.EAGER)` — `User.roles`: a `Set<RoleType>` stored in a
  separate collection table (RoleType is a plain enum, not an entity), forced EAGER because
  `getAuthorities()` needs it during authentication.
- `@Enumerated(EnumType.STRING)` — used on `User.providerType`, `Patient.bloodGroup`, and the
  `roles` element collection; stores the enum name as text so reordering enum constants never
  corrupts existing data.
- `@ManyToOne(fetch = FetchType.LAZY)` vs default `@ManyToOne` — `Appointment.doctor` is explicitly
  LAZY, `Appointment.patient` relies on the JPA default (EAGER for `@ManyToOne`/`@OneToOne`).
- `@OneToMany(mappedBy=..., cascade=..., orphanRemoval=..., fetch=...)` — `Patient.appointments`
  combines `cascade=CascadeType.REMOVE` + `orphanRemoval=true` + `fetch=EAGER`; deleting a Patient
  cascades delete to their Appointments.
- `@JoinTable` — `Department.doctors` defines the M:N join table `my_dpt_doctors` explicitly with
  named join/inverse columns (`dpt_id`, `doctor_id`).
- `@CreationTimestamp` + `@Column(updatable = false)` — `Insurance.createdAt` and
  `Patient.createdAt`: Hibernate stamps the value once on INSERT, never touched again.
- `@ToString.Exclude` on `Appointment.patient`/`Appointment.doctor` — avoids recursive/lazy-init
  bugs when Lombok's generated `toString()` would otherwise traverse into associated entities.

## Common mistakes to watch for

- **`Patient`'s email unique constraint is commented out** in the `@Table(uniqueConstraints = ...)`
  block, while the `@Column(unique = true, nullable = false)` on the `email` field still enforces
  it independently. Notice the redundancy/inconsistency rather than "fixing" it — it's left as an
  example of an evolving schema decision.
- **`PatientController.getPatientProfile()` hardcodes `patientId = 4L`** (documented in the
  `controller` package, but worth knowing when reasoning about Patient/Appointment loading) — not
  something this package fixes, just a rough edge to be aware of when tracing a request.
- Mixing up which side "owns" a relationship is the easiest mistake in this codebase: e.g. updating
  `Doctor.departments` (the inverse `mappedBy` side) alone does **not** persist a M:N change — only
  writes through `Department.doctors` (the owning side) hit the `my_dpt_doctors` table.
- Assuming `patient.getAppointments().add(appointment)` (updating the inverse in-memory collection)
  causes a DB write — it doesn't; only setting the owning FK field (`appointment.setPatient(...)`)
  does. See `AppointmentService` for the pattern of updating both sides for object-graph
  consistency.
- Forgetting that `orphanRemoval=true` on `Patient.insurance` means simply calling
  `patient.setInsurance(null)` deletes the Insurance row on flush — it is not a harmless
  "detach reference" operation.
- `@ElementCollection` fields like `User.roles` live in their own collection table, not a real
  entity with its own repository — you cannot query "all users with role X" via a normal
  `@ManyToOne` join the way you would for a real entity association.

## Best practices demonstrated here

- Use `EnumType.STRING` for any enum persisted to the database, not the ordinal default —
  protects against silent data corruption when enum constants are reordered or inserted.
- Be explicit about fetch type on associations that matter for performance (`Appointment.doctor`
  LAZY) rather than relying on JPA defaults everywhere, especially for `@ManyToOne`/`@OneToOne`
  which default to EAGER.
- Exclude bidirectional/lazy associations from generated `toString()`/`equals()`/`hashCode()` to
  avoid recursion and unwanted lazy-loading triggers (see `@ToString.Exclude` in `Appointment`).
- Keep the "owning side" of a relationship unambiguous and document it in a comment
  (`// owning side`, `// inverse side`) right at the mapping, as `Patient.insurance` and
  `Insurance.patient` already do.

## Interview questions

1. **Q: What does `@MapsId` do on `Doctor.user`, and why use it instead of a normal FK column?**
   A: It makes `Doctor`'s primary key identical to the associated `User`'s primary key (a shared
   primary key pattern), instead of generating an independent `doctor_id` sequence with a separate
   FK column. This models "a Doctor IS a specialization of a User row" directly at the schema level.

2. **Q: Why is `User.roles` forced to `FetchType.EAGER` when the JPA default for
   `@ElementCollection` is LAZY?**
   A: `User.getAuthorities()` (required by `UserDetails`) reads `roles` synchronously while Spring
   Security builds the `SecurityContext`, which can happen after the original persistence context
   that loaded the `User` is gone. A LAZY collection would throw
   `LazyInitializationException` at that point, so EAGER is required for correctness, not just performance.

3. **Q: In the Doctor↔Department many-to-many, which side is "owning," and what would happen if
   you only updated the inverse side?**
   A: `Department` owns the relationship (it declares `@JoinTable my_dpt_doctors`).
   `Doctor.departments` is the inverse side (`mappedBy="departments"`). Adding a Department to
   `doctor.getDepartments()` alone updates only the in-memory object graph — Hibernate will not
   write anything to `my_dpt_doctors` unless the change is also made through `Department.doctors`.

4. **Q: Explain what happens, step by step, when `InsuranceService.disaccociateInsuranceFromPatient`
   sets `patient.setInsurance(null)`.**
   A: `Patient.insurance` is configured with `cascade=CascadeType.ALL, orphanRemoval=true`. Setting
   it to null doesn't just clear the FK column — because `orphanRemoval=true`, Hibernate treats the
   previously-referenced `Insurance` row as an orphan at flush time and issues a DELETE for it, all
   within the enclosing `@Transactional` method.

5. **Q: Why is `Appointment.doctor` explicitly `fetch = FetchType.LAZY` while `Appointment.patient`
   is left at the default?**
   A: Both are `@ManyToOne`, which defaults to EAGER in JPA. `doctor` is explicitly overridden to
   LAZY so that loading an `Appointment` doesn't always also load a full `Doctor` (returned instead
   as a Hibernate proxy until actually accessed) — while `patient` uses the default EAGER since
   Patient details are almost always needed alongside an Appointment in this app's flows. It's also
   used deliberately as a fetch-type contrast for learning purposes.

## Summary

The `entity/` package models the hospital domain with JPA/Hibernate, centered on `User` as both a
persistence entity and the Spring Security principal. Doctor and Patient share their primary key
with User; Department and Doctor are linked M:N; Patient fully owns its Insurance's lifecycle; and
Appointment sits at the M:1 junction between Patient and Doctor with intentionally different fetch
strategies on each side. The `entity/type/` sub-package supplies the plain enums (`RoleType`,
`PermissionType`, `AuthProviderType`, `BloodGroupType`) that back these mappings, all persisted as
strings for schema stability.

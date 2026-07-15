# MASTER_NOTES — Hospital Management System (Single Source of Truth)

This document is the map of the whole project. Package-level `notes.md` files go deep on one package;
`NOTES/` goes deep on one JPA concept; this file connects everything end to end, in the order things
actually happen at runtime. Every claim here is grounded in the real code in this repository — file paths
are given so you can jump straight to the source.

---

## 1. Architecture at a glance

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         HTTP Client (browser / curl)                    │
└───────────────────────────────┬───────────────────────────────────────┘
                                 │  /api/v1/**  (context-path, application.properties)
                                 ▼
                    ┌─────────────────────────┐
                    │   JwtAuthFilter          │  security/JwtAuthFilter.java
                    │   (OncePerRequestFilter) │  reads Authorization: Bearer <jwt>
                    └────────────┬────────────┘
                                 │ sets SecurityContextHolder if token valid
                                 ▼
                    ┌─────────────────────────┐
                    │  Spring Security filter  │  security/WebSecurityConfig.java
                    │  chain (URL rules,       │  authorizeHttpRequests + @EnableMethodSecurity
                    │  oauth2Login, exception  │
                    │  handling)               │
                    └────────────┬────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │      @RestController     │  controller/*.java  — thin, delegates only
                    └────────────┬────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │        @Service          │  service/*.java, security/AuthService.java
                    │  @Transactional business │  — DTO <-> Entity mapping via ModelMapper
                    │  logic                   │
                    └────────────┬────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │  Spring Data JPA         │  repository/*.java — interfaces only,
                    │  Repository              │  Spring generates the implementation
                    └────────────┬────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │  Hibernate / EntityManager│ persistence context, dirty checking,
                    │  / Persistence Context    │ SQL generation
                    └────────────┬────────────┘
                                 ▼
                            PostgreSQL
```

---

## 2. Startup flow

1. `HospitalManagementApplication.main()` calls `SpringApplication.run(...)`
   (`src/main/java/.../HospitalManagementApplication.java`).
2. `@SpringBootApplication` triggers:
   - **Component scanning** — every `@Component`/`@Service`/`@Repository`/`@Controller`/`@Configuration`
     under `com.codingshuttle.youtube.hospitalManagement` is discovered and registered as a bean definition.
   - **Auto-configuration** — because `spring-boot-starter-data-jpa` and `postgresql` are on the classpath
     (`pom.xml`), Spring Boot auto-configures a `DataSource` from `application.properties`
     (`spring.datasource.*`), a `LocalContainerEntityManagerFactoryBean`, and a `JpaTransactionManager` —
     none of these beans are defined by hand anywhere in this codebase.
3. Hibernate reads every `@Entity` class (`entity/*.java`) and, because
   `spring.jpa.hibernate.ddl-auto=create`, generates and executes DDL to drop and recreate every table —
   this is why the schema always matches the entity annotations exactly, with no separate migration file.
4. Bean instantiation order (roughly): low-level infra beans (DataSource, ObjectMapper) → repositories
   (proxied interfaces, no user code) → `AppConfig` beans (`ModelMapper`, `PasswordEncoder`,
   `AuthenticationManager`) → services (constructor-injected via `@RequiredArgsConstructor`, depend on
   repositories + `AppConfig` beans) → security components (`JwtAuthFilter`, `OAuth2SuccessHandler` depend
   on services) → `WebSecurityConfig` (depends on the security components) → controllers (depend on
   services) → embedded Tomcat starts and begins accepting requests.
5. Because everything here uses **constructor injection** via Lombok's `@RequiredArgsConstructor` (see
   every `@Service`/`@Component` in `service/`, `security/`, `controller/`), Spring resolves the entire
   dependency graph at startup and fails fast if anything is missing or circular — there is no
   `@Autowired`-on-field anywhere in this codebase to hide a missing dependency until runtime.

---

## 3. Dependency injection in this project

- **Constructor injection everywhere** — `@RequiredArgsConstructor` (Lombok) generates a constructor for
  all `final` fields; Spring uses that constructor. Example: `service/AppointmentService.java` depends on
  `AppointmentRepository`, `DoctorRepository`, `PatientRepository`, `ModelMapper` — all `final`, all
  supplied by Spring at construction time. This makes dependencies explicit and the class impossible to
  construct in an invalid (partially-wired) state.
- **Interface-based repositories** — `repository/*Repository.java` are plain interfaces extending
  `JpaRepository<Entity, Id>`. Spring Data JPA generates a runtime proxy implementation; there is no
  hand-written implementation class anywhere in the codebase.
- **Bean definitions in `config/AppConfig.java`** — `ModelMapper`, `PasswordEncoder` (BCrypt),
  `AuthenticationManager` are supplied here because they're third-party types Spring Boot doesn't
  auto-configure on its own.

---

## 4. Request lifecycle — walked through with a real endpoint

**Example: `POST /api/v1/patients/appointments` (create an appointment)**

1. Request hits `JwtAuthFilter.doFilterInternal` (`security/JwtAuthFilter.java`) — extracts the JWT,
   resolves the username via `AuthUtil.getUsernameFromToken`, loads the `User` entity via
   `UserRepository.findByUsername`, and puts a `UsernamePasswordAuthenticationToken(user, null,
   user.getAuthorities())` into `SecurityContextHolder`. Note: **the security principal IS the JPA entity**
   — `User implements UserDetails` directly (`entity/User.java`), no separate DTO/adapter layer.
2. Spring Security's filter chain (`security/WebSecurityConfig.java`) checks the URL rule
   (`.anyRequest().authenticated()` — `/patients/**` isn't in the public matchers) — passes.
3. `PatientController.createNewAppointment` (`controller/PatientController.java`) receives the
   deserialized `CreateAppointmentRequestDto` and calls straight into
   `AppointmentService.createNewAppointment` — the controller does no business logic itself.
4. `AppointmentService.createNewAppointment` (`service/AppointmentService.java`), inside `@Transactional`
   and guarded by `@Secured("ROLE_PATIENT")` (method-level security, checked *after* the URL-level check,
   enabled by `@EnableMethodSecurity` in `WebSecurityConfig`):
   - loads the `Patient` and `Doctor` via their repositories (`orElseThrow` → 404-style `EntityNotFoundException`
     if missing — handled centrally by `error/GlobalExceptionHandler`),
   - builds a new `Appointment` via its Lombok `@Builder`,
   - sets both sides of the bidirectional association (`appointment.setDoctor(doctor)` — the FK-owning
     side — **and** `patient.getAppointments().add(appointment)` — the in-memory inverse side, for object
     graph consistency; only the owning side affects what's persisted),
   - `appointmentRepository.save(appointment)` — Spring Data JPA calls `EntityManager.persist(...)`
     (new entity → INSERT),
   - maps the saved entity to `AppointmentResponseDto` via the `ModelMapper` bean, so the entity (with its
     lazy `doctor`/`patient` associations) never leaks past the service boundary.
5. `GlobalExceptionHandler` (`error/GlobalExceptionHandler.java`) would intercept any exception thrown
   anywhere in this chain and convert it to a consistent `ApiError` JSON response — this is why none of the
   controllers/services have their own try/catch blocks.

---

## 5. Transaction lifecycle

- `@Transactional` (here, `jakarta.transaction.Transactional`, honored by Spring's JTA-aware transaction
  support) opens a transaction and a Hibernate `Session` (== persistence context) at method entry, and
  commits (flushing pending SQL) at method exit if no exception propagated — or rolls back if one did.
- **Canonical dirty-checking example** — `AppointmentService.reAssignAppointmentToAnotherDoctor`
  (`service/AppointmentService.java`): loads a managed `Appointment` via `findById`, calls
  `appointment.setDoctor(doctor)`, and returns — **there is no explicit `.save()` call**. Because the
  entity was loaded inside an active transaction (still "managed," tracked by the persistence context),
  Hibernate compares its in-memory state against the snapshot taken at load time when the transaction
  commits, detects the changed `doctor` field, and issues an UPDATE automatically. This single method is
  the best place in this codebase to observe dirty checking — pair it with `spring.jpa.show-sql=true` and
  watch the UPDATE appear with no corresponding `save()` call in the Java code.
- **What loses dirty checking**: `PatientRepository.updateNameWithId` (`repository/PatientRepository.java`)
  is a `@Modifying` bulk JPQL `UPDATE` — it talks straight to the database, bypassing the persistence
  context entirely. If a `Patient` for that same id is already loaded in the current session, it becomes
  **stale** (holds the old name) until reloaded/refreshed — bulk updates and dirty checking do not mix
  automatically.
- **Cascade + transaction interaction** — `InsuranceService.disaccociateInsuranceFromPatient`
  (`service/InsuranceService.java`) sets `patient.setInsurance(null)`; because `Patient.insurance` is
  declared `cascade=CascadeType.ALL, orphanRemoval=true` (`entity/Patient.java`), Hibernate schedules a
  DELETE for the now-orphaned `Insurance` row at the same flush/commit point — no explicit
  `insuranceRepository.delete(...)` call needed.

---

## 6. Entity lifecycle & persistence context

Four states an entity instance can be in: **Transient** (built with `new`/`@Builder`, not yet known to
Hibernate — e.g. the `Appointment.builder()...build()` call in `AppointmentService` before `.save()`),
**Managed** (returned by `repository.findById(...)`/`save(...)` inside an active persistence context —
changes are tracked), **Detached** (was managed, but the persistence context closed — e.g. an entity handed
back from a `@Transactional` service method to a non-transactional caller; further field changes are
**not** tracked), **Removed** (scheduled for deletion, e.g. via `orphanRemoval` or explicit `.delete(...)`).

The **persistence context** (Hibernate `Session`, one per transaction here since there's no
`OpenEntityManagerInViewFilter` override) is Hibernate's first-level cache and change-tracking table: every
entity loaded during a transaction is registered in it, and it's what makes dirty checking (§5) and
`@MapsId` shared-key associations possible without extra queries.

**Lazy loading / proxies** — `Appointment.doctor` is `@ManyToOne(fetch = FetchType.LAZY)`
(`entity/Appointment.java`): when an `Appointment` is loaded, `doctor` is initially a Hibernate-generated
proxy (subclass with no real data) that only issues a SELECT the first time a getter on it is called. This
only works *while the persistence context is still open* — accessing a lazy field after the transaction has
committed (entity now detached) throws `LazyInitializationException`. Contrast: `Patient.appointments` is
explicitly `fetch = FetchType.EAGER` (`entity/Patient.java`) — always loaded immediately, a deliberate
teaching contrast kept in this codebase (see `NOTES/11. Lazy vs Eager Loading.md`).

---

## 7. Hibernate SQL generation

Turn on `spring.jpa.show-sql=true` (already set in `application.properties`) and you can watch, live, the
different SQL-generation paths in this codebase:
- **Derived query methods** (`PatientRepository.findByName`, `findByBirthDateBetween`, etc.) — Spring Data
  parses the method name at *startup* into a query plan; no reflection cost per call.
- **JPQL** (`PatientRepository.findByBloodGroup`, `findByBornAfterDate`, `findAllPatientWithAppointment`) —
  written against entity/field names, translated to SQL by Hibernate at query-execution time.
- **JPQL constructor-expression projection** (`PatientRepository.countEachBloodGroupType`) — Hibernate
  builds `BloodGroupCountResponseEntity` DTOs directly from the `SELECT new ...(...)` clause, skipping full
  entity hydration.
- **Native SQL** (`PatientRepository.findAllPatients`, `@Query(nativeQuery = true)`) — the literal SQL you
  wrote, run as-is (Spring Data still applies `LIMIT`/`OFFSET` for the `Pageable` argument even though the
  query is native).
- **DDL generation** — the entire schema (§2) is generated from `@Entity`/`@Column`/`@Table`/`@Index`
  annotations because of `ddl-auto=create`.

---

## 8. Security flow (see also `security/notes.md` for sequence diagrams)

- **JWT path**: `JwtAuthFilter` → `AuthUtil` (parse/verify) → `UserRepository` → `SecurityContextHolder`.
  Stateless (`SessionCreationPolicy.STATELESS` in `WebSecurityConfig`) — every request re-authenticates from
  its own token, nothing is kept server-side between requests.
- **OAuth2 path**: Spring Security's built-in `oauth2Login()` flow runs, then hands control to
  `OAuth2SuccessHandler`, which calls `AuthService.handleOAuth2LoginRequest` to find-or-create a `User` (and
  a linked `Patient`, via the same `signUpInternal` used by plain signup) and writes back the **same**
  `LoginResponseDto` JWT contract as normal login — so callers don't need to know which auth method was
  used.
- **Authorization layering**: URL-level rules in `WebSecurityConfig.securityFilterChain` (coarse — e.g.
  `/admin/**` needs `ROLE_ADMIN`) plus method-level `@Secured`/`@PreAuthorize` on service methods (finer —
  e.g. `AppointmentService.reAssignAppointmentToAnotherDoctor`'s
  `@PreAuthorize("hasAuthority('appointment:write') or #doctorId == authentication.principal.id")` lets a
  doctor act on their own appointments even without the blanket write permission). Both layers read
  authorities computed by `User.getAuthorities()`, which expands each coarse `RoleType` into fine-grained
  `PermissionType` strings via the static `RolePermissionMapping` table (`security/RolePermissionMapping.java`)
  — RBAC policy lives in one place, not scattered across annotations.

---

## 9. Exception flow

`error/GlobalExceptionHandler` (`@RestControllerAdvice`) centralizes every error response into the same
`ApiError` JSON shape (`error/ApiError.java`). Because filters (like `JwtAuthFilter`) run *outside* Spring
MVC's normal `@ExceptionHandler` reach, they explicitly delegate to `HandlerExceptionResolver.resolveException(...)`
(injected into `JwtAuthFilter`, `WebSecurityConfig`'s `accessDeniedHandler`, and the OAuth2 `failureHandler`)
so that even filter-level and security-level failures still end up shaped by the same
`GlobalExceptionHandler`.

---

## 10. Testing flow

See `src/test/.../notes.md` — the existing tests are manual exploration harnesses
(`@SpringBootTest` against a real database, assertion-free, lots of commented-out experiments), useful for
building JPA intuition but not a production regression suite.

---

## 11. Configuration flow

See `src/main/resources/notes.md` for the full breakdown of `application.properties`, `application.yml`,
and `data.sql`.

---

## 12. Where to go next

- Package-level detail: `entity/notes.md`, `repository/notes.md`, `dto/notes.md`, `service/notes.md`,
  `controller/notes.md`, `security/notes.md`, `error/notes.md`, `config/notes.md`.
- Concept-by-concept deep dives, in suggested study order: `NOTES/01. Spring Data JPA Architecture.md`
  through `NOTES/18. Cheat Sheet.md`.
- Project-wide learning roadmap: `README.md`.

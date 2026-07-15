# Hospital Management System — A Spring Data JPA Learning Textbook

> Spring Boot project built to **master Spring Boot Data JPA** through a realistic, real-world application
> (a hospital management system), not toy examples.

This repository has been turned into a self-contained, interactive textbook. The code is a working Spring
Boot 3 / Java 21 app; layered on top of it is a complete documentation system — inline "why" comments in
every class, a `notes.md` in every package, a single-source-of-truth `src/MASTER_NOTES.md`, and an 18-part
concept curriculum in `NOTES/`. If you already know Java, OOP, JDBC, Hibernate, JPA basics, and Spring Boot
basics, this project connects those dots end to end: **Spring → JPA → Hibernate → THIS project.**

---

## 1. Project overview

A hospital management backend exposing REST APIs for patients, doctors, appointments, insurance, and admin
operations, secured with **JWT + OAuth2 login** and a custom **role/permission (RBAC)** model. It's
deliberately feature-rich so that nearly every Spring Data JPA concept shows up in a real usage context:

| Concept | Where it lives in this project |
|---|---|
| Entity mapping & constraints | `entity/User`, `entity/Patient` (composite unique constraint, indexes, `@Enumerated(STRING)`) |
| Shared primary key (`@MapsId`) | `entity/Doctor`, `entity/Patient` share the `User` PK |
| All relationship types | `@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany` across the 6 entities |
| Cascade & orphan removal | `Patient.insurance` (`CascadeType.ALL` + `orphanRemoval`), `Patient.appointments` (`REMOVE`) |
| Lazy vs eager fetching | `Appointment.doctor` (LAZY) vs `Appointment.patient` (EAGER); `Patient.appointments` (EAGER) vs `Doctor.appointments` (LAZY) |
| Derived query methods | `PatientRepository.findByBirthDateBetween`, `findByNameContainingOrderByIdDesc`, … |
| JPQL, projections, native SQL | `PatientRepository.findByBloodGroup`, `countEachBloodGroupType`, `findAllPatients` |
| `@Modifying` bulk update | `PatientRepository.updateNameWithId` |
| `JOIN FETCH` (N+1 fix) | `PatientRepository.findAllPatientWithAppointment` |
| Pagination & sorting | `PatientService.getAllPatients` + `Pageable` |
| Dirty checking (no `save()`) | `AppointmentService.reAssignAppointmentToAnotherDoctor` |
| Transactions | `@Transactional` across the service layer |
| DTO mapping | `ModelMapper` bean + all `dto/*` |
| Central exception handling | `error/GlobalExceptionHandler` (`@RestControllerAdvice`) |
| Security (JWT + OAuth2 + RBAC) | `security/*` package |

---

## 2. Architecture

```
HTTP client
    │  (/api/v1/**)
    ▼
JwtAuthFilter ──► Spring Security filter chain ──► @RestController ──► @Service ──► JpaRepository
(security/)        (WebSecurityConfig)              (controller/)      (@Transactional)   (interface only)
                                                                        (service/)        (repository/)
                                                                            │                   │
                                                                     ModelMapper          Hibernate /
                                                                     entity⇄DTO           EntityManager /
                                                                                          Persistence Context
                                                                                                │
                                                                                            PostgreSQL
```

Full narrative — startup flow, bean lifecycle, request lifecycle, transaction/entity lifecycle, persistence
context, SQL generation, security flow — is in **[`src/MASTER_NOTES.md`](src/MASTER_NOTES.md)**.

---

## 3. Folder structure

```
spring-boot-data-jpa-hospital-management-system/
├── README.md                     ← you are here (learning guide)
├── NOTES/                        ← 18-part Spring Data JPA concept curriculum (start here to learn)
│   ├── 01. Spring Data JPA Architecture.md
│   ├── 02. Entity Mapping.md
│   ├── … (03–17) …
│   └── 18. Cheat Sheet.md
└── src/
    ├── MASTER_NOTES.md            ← single source of truth: whole system, end to end
    ├── main/
    │   ├── java/…/hospitalManagement/
    │   │   ├── config/            ← AppConfig (ModelMapper, PasswordEncoder, AuthenticationManager)  + notes.md
    │   │   ├── controller/        ← thin REST controllers                                            + notes.md
    │   │   ├── dto/               ← API-boundary request/response shapes                             + notes.md
    │   │   ├── entity/            ← @Entity classes + entity/type enums                              + notes.md
    │   │   ├── error/             ← ApiError + GlobalExceptionHandler                                + notes.md
    │   │   ├── repository/        ← Spring Data JPA repositories                                     + notes.md
    │   │   ├── security/          ← JWT, OAuth2, RBAC, filters, config                               + notes.md
    │   │   └── service/           ← @Transactional business logic                                    + notes.md
    │   └── resources/             ← application.properties/.yml, data.sql                            + notes.md
    └── test/                      ← JPA exploration harnesses                                        + notes.md
```

Every package folder contains a `notes.md` explaining that package's purpose, interactions, annotations,
common mistakes, best practices, and interview questions — grounded in this project's real code.

---

## 4. Setup

**Prerequisites:** Java 21, Maven, PostgreSQL running locally.

1. Create the database:
   ```sql
   CREATE DATABASE "hospitalDB";
   ```
2. Set your DB credentials in `src/main/resources/application.properties`
   (`spring.datasource.username` / `spring.datasource.password`).
3. (Optional) For OAuth2 login, put real Google/GitHub client IDs/secrets in `application.yml`. Not needed
   for plain email/JWT signup+login.
4. Run:
   ```bash
   ./mvnw spring-boot:run
   ```
   All endpoints are served under the context path **`/api/v1`** (e.g. `POST /api/v1/auth/signup`).

> ⚠️ **Learning-project settings, not production:** `spring.jpa.hibernate.ddl-auto=create` drops and
> recreates the schema on every restart; the JWT secret and OAuth2 client secrets are committed in plaintext.
> See `src/main/resources/notes.md` for why, and what the production-safe alternatives are.

Turn on `spring.jpa.show-sql=true` (already set) and watch the console while exercising the app — *seeing* the
generated SQL is the fastest way to build JPA intuition.

---

## 5. Request flow (quick reference)

| Endpoint (under `/api/v1`) | Controller | Service | Notes |
|---|---|---|---|
| `POST /auth/signup`, `POST /auth/login` | `AuthController` | `AuthService` | public; login returns a JWT |
| `GET /public/doctors` | `HospitalController` | `DoctorService` | public |
| `POST /patients/appointments` | `PatientController` | `AppointmentService` | authenticated patient |
| `GET /doctors/appointments` | `DoctorController` | `AppointmentService` | DOCTOR/ADMIN; reads principal from `SecurityContextHolder` |
| `GET /admin/patients`, `POST /admin/onBoardNewDoctor` | `AdminController` | `PatientService`/`DoctorService` | ADMIN |

Traced step-by-step in `NOTES/16. Project Walkthrough.md` and `src/MASTER_NOTES.md` §4.

---

## 6. Learning roadmap (suggested study order)

Read in this order — each builds on the last. Every doc follows the same 14-section format (Definition → Core
Idea → Why → Internal Working → Flow Diagram → Analogy → How THIS project uses it → Code Example →
Line-by-Line → Common Mistakes → Best Practices → Performance → Interview Questions → Summary).

**Foundations**
1. `NOTES/01. Spring Data JPA Architecture.md` — where Spring Data sits between your code and the database
2. `NOTES/02. Entity Mapping.md` — turning classes into tables
3. `NOTES/03. Repository Internals.md` — how a bare interface becomes a working repository
4. `NOTES/04. Query Methods.md` — derived queries from method names
5. `NOTES/05. JPQL vs Native Queries.md` — `@Query`, projections, native SQL, `@Modifying`
6. `NOTES/06. Entity Relationships.md` — `@OneToOne`/`@OneToMany`/`@ManyToMany`, owning vs inverse side

**The engine (how JPA/Hibernate actually behaves)**
7. `NOTES/07. Persistence Context.md`
8. `NOTES/08. Entity Lifecycle.md` — transient / managed / detached / removed
9. `NOTES/09. Dirty Checking.md` — the "update without `save()`" magic ⭐
10. `NOTES/10. Transactions in Spring Data JPA.md`
11. `NOTES/11. Lazy vs Eager Loading.md`
12. `NOTES/12. Cascade & orphanRemoval.md`

**Applied & performance**
13. `NOTES/13. Pagination & Sorting.md`
14. `NOTES/14. Performance & N+1 Problem.md`
15. `NOTES/15. DTO Mapping.md`
16. `NOTES/16. Project Walkthrough.md` — full end-to-end tours through the real code

**Revision**
17. `NOTES/17. Interview Revision.md` — 35 Q&A by topic
18. `NOTES/18. Cheat Sheet.md` — dense annotation / FetchType / CascadeType lookup tables

Alongside the code itself: read the inline comments in each class, then that package's `notes.md`, then the
matching `NOTES/` concept doc.

---

## 7. Important concepts to internalize

- **A repository is just an interface** — Spring Data generates the implementation at runtime
  (`NOTES/03`). You never write a `SimpleJpaRepository`.
- **Dirty checking** — inside a transaction, mutating a *managed* entity persists automatically at flush; no
  `save()` needed. Canonical example: `AppointmentService.reAssignAppointmentToAnotherDoctor` (`NOTES/09`).
- **Owning vs inverse side** — only the FK-owning side controls what's written to the DB; the `mappedBy` side
  is just a mirror. Get this wrong and your updates "silently don't persist" (`NOTES/06`).
- **Lazy loading needs an open persistence context** — access a lazy association after the transaction ends
  and you get `LazyInitializationException`; this is *why* services map to DTOs before returning (`NOTES/11`,
  `NOTES/15`).
- **Fetch type changes *when*, not *whether*, associations load** — EAGER can still cause N+1; `JOIN FETCH`
  fixes it (`NOTES/14`).
- **`@Modifying` bulk updates bypass the persistence context** — they don't trigger dirty checking and can
  leave already-loaded entities stale (`NOTES/05`, `NOTES/09`).

---

## 8. Interview topics covered

Entities & mapping · shared primary keys (`@MapsId`) · `JpaRepository` vs `CrudRepository` · derived queries ·
JPQL vs native vs projections · `@Modifying` · persistence context / first-level cache · entity lifecycle
states · dirty checking · `@Transactional` propagation · lazy vs eager · cascade & orphan removal · the N+1
problem & `JOIN FETCH` · pagination · DTO mapping strategies · why `User implements UserDetails` works ·
stateless JWT security & method-level authorization.

Consolidated Q&A: `NOTES/17. Interview Revision.md`. Quick lookup: `NOTES/18. Cheat Sheet.md`.

---

## 9. Learning checklist

Tick these off as you go — each maps to a place in the real code you can run and inspect:

- [ ] Explain why `Doctor` and `Patient` share the `User` primary key (`@MapsId`)
- [ ] Trace a `POST /auth/signup` request from controller to two DB inserts
- [ ] Point to the exact method that updates a row **without** calling `save()`, and explain why it works
- [ ] Predict the SQL for `findAllPatientWithAppointment` vs a plain `findAll()` and explain the N+1 difference
- [ ] Explain what `orphanRemoval=true` does when `patient.setInsurance(null)` is called
- [ ] Say what happens if you access `appointment.getDoctor().getName()` after the transaction has closed
- [ ] Distinguish the four entity lifecycle states using real objects in `AppointmentService`
- [ ] Explain why every service returns a DTO instead of the entity
- [ ] Describe how a `RoleType` becomes a set of fine-grained Spring Security authorities (`RolePermissionMapping`)
- [ ] Rebuild the `@Query` for `countEachBloodGroupType` from memory, including the constructor expression

---

## 10. Documentation map (where to find what)

| I want to understand… | Read |
|---|---|
| The whole system, end to end | `src/MASTER_NOTES.md` |
| One JPA concept in depth | `NOTES/01`–`18` |
| One package's role | that package's `notes.md` |
| Why a specific line exists | the inline comments in that `.java` file |
| A fast annotation lookup | `NOTES/18. Cheat Sheet.md` |
| Interview prep | `NOTES/17. Interview Revision.md` |

Happy learning. Read the code, read the comments, run it with `show-sql` on, and connect what you see to the
concept docs.

# `config/` package notes

## Purpose

Holds application-wide `@Configuration` classes that wire cross-cutting beans not tied to a single
feature. Currently the package's central class is `AppConfig`, which supplies infrastructure beans
consumed throughout the service and security layers: `ModelMapper`, `PasswordEncoder`, and
`AuthenticationManager`.

(Note: `WebSecurityConfig`, which configures the security filter chain itself, lives in the
`security/` package per the architecture briefing — `config/` here is specifically the smaller,
general-purpose bean definitions.)

## Responsibilities

- Expose a single `ModelMapper` bean so every service can convert entities to/from DTOs the same
  way, without hand-rolling mapping code per feature.
- Expose the `PasswordEncoder` (BCrypt) used both to hash passwords on signup and to verify them on
  login.
- Expose Spring Security's `AuthenticationManager` as an injectable bean, since Spring Boot
  auto-configuration builds one internally but doesn't publish it by default.
- Preserve, in commented-out form, an earlier `InMemoryUserDetailsManager`-based `userDetailsService`
  bean as a historical snapshot of "before users were DB-backed."

## How it interacts with other packages

- **service/**: Every service that maps entities to DTOs (`PatientService`, `DoctorService`,
  `AppointmentService`, etc.) injects the `ModelMapper` bean from here.
- **security/**: `AuthService.login()` uses the `AuthenticationManager` bean to authenticate
  credentials, which internally delegates to `CustomUserDetailsService.loadUserByUsername` and the
  `PasswordEncoder` bean defined here (BCrypt hash comparison). `AuthService`/`AdminController` also
  use the `PasswordEncoder` directly when creating new local accounts.
- **entity/**: `AppConfig.userDetailsService()` (commented out) referenced Spring Security's own
  `org.springframework.security.core.userdetails.User` — note this is a *different* class from this
  project's own `entity.User` (which is the one actually used today, since it implements
  `UserDetails` directly). The import of Spring's `User` alongside the app's own `entity.User`
  elsewhere in the codebase is a common point of confusion.

## Important annotations/beans used in this project (with real examples)

- `@Configuration` on `AppConfig` — marks the class as a source of `@Bean` definitions processed at
  context startup.
- `@Bean public ModelMapper modelMapper()` — a single shared, stateless `ModelMapper` instance
  reused across all services (`modelMapper.map(entity, XDto.class)` pattern).
- `@Bean public PasswordEncoder passwordEncoder()` — returns `new BCryptPasswordEncoder()`; used
  both for encoding on signup and (indirectly, via `DaoAuthenticationProvider` under the hood of
  `AuthenticationManager`) for verifying on login.
- `@Bean public AuthenticationManager authenticationManager(AuthenticationConfiguration
  configuration)` — pulls the manager Spring Security already built internally
  (`configuration.getAuthenticationManager()`) and republishes it as an application bean so
  `AuthService` can inject and call it directly.

## Common mistakes to watch for

- The commented-out `userDetailsService()` bean is **not dead code to delete** — it's a deliberate
  historical artifact showing the in-memory-users approach that predates the current DB-backed
  `CustomUserDetailsService`. Worth reading once to understand the evolution of the auth setup, but
  leave it commented out.
- That same commented-out method imports `org.springframework.security.core.userdetails.User` — easy
  to confuse with this project's own `entity.User`. If you ever re-enable it, note the two `User`
  classes would need to be disambiguated (fully-qualified name or alias import).
- `AppConfig.authenticationManager(...)` throws a checked `Exception` (from
  `AuthenticationConfiguration.getAuthenticationManager()`) — a detail that surprises people who
  expect bean factory methods to be exception-free.

## Best practices demonstrated here

- Centralizing infrastructure beans (`ModelMapper`, `PasswordEncoder`, `AuthenticationManager`) in
  one configuration class rather than scattering `@Bean` definitions across feature packages, so
  there's one obvious place to look for "where do these get wired up."
- Re-publishing Spring Security's internally-constructed `AuthenticationManager` explicitly as a
  bean rather than trying to build a competing one by hand — avoids duplicating Spring Boot's
  autoconfigured `DaoAuthenticationProvider` wiring.
- Keeping password hashing behind a `PasswordEncoder` interface bean (BCrypt today) rather than
  hardcoding the algorithm at each call site, so the hashing strategy can be swapped in one place.

## Interview questions

1. **Q: Why does `AppConfig` need to explicitly expose an `AuthenticationManager` bean — doesn't
   Spring Boot configure one automatically?**
   A: Spring Boot's security auto-configuration builds an `AuthenticationManager` internally but
   does not expose it as an injectable bean by default. `AppConfig.authenticationManager(...)` pulls
   it out via `AuthenticationConfiguration.getAuthenticationManager()` so `AuthService` can call
   `authenticate(...)` on it directly.

2. **Q: What role does the `PasswordEncoder` bean play at both signup and login time in this
   project?**
   A: At signup, it hashes the plaintext password before saving the `User` entity (so raw passwords
   are never persisted). At login, `AuthenticationManager` internally uses
   `CustomUserDetailsService.loadUserByUsername` plus this same `PasswordEncoder` to compare the
   submitted password's hash against the stored one — the encoder is never called manually at login
   in `AuthService`, it's invoked inside the authentication provider Spring wires up.

3. **Q: Why keep a commented-out `InMemoryUserDetailsManager` bean in the codebase instead of
   deleting it?**
   A: It documents the evolution of the security setup — before `CustomUserDetailsService` and
   DB-backed `User` accounts existed, authentication ran against two hardcoded in-memory accounts.
   Leaving it commented (rather than deleting or re-enabling it) preserves that history for learners
   without it interfering with the current DB-backed flow.

4. **Q: If two `@Bean` methods for `UserDetailsService` existed simultaneously — the commented-out
   one here and `CustomUserDetailsService` in `security/` — what would happen?**
   A: Spring context startup would fail (or behave ambiguously) due to duplicate/conflicting
   `UserDetailsService` beans, unless one was marked `@Primary` or qualified. That's part of why the
   in-memory one here is kept commented out rather than active alongside `CustomUserDetailsService`.

5. **Q: Why centralize the `ModelMapper` bean in `config/` instead of `new ModelMapper()`-ing it
   inside each service?**
   A: A single shared instance avoids repeated object churn and, more importantly, keeps mapping
   configuration (if ever customized, e.g. custom converters) in one place rather than duplicated or
   drifting across every service that needs entity↔DTO conversion.

## Summary

`config/` is the small, general-purpose home for infrastructure beans this project's services and
security layer depend on: `ModelMapper` for DTO conversion, `PasswordEncoder` (BCrypt) for password
hashing/verification, and a republished `AuthenticationManager` for `AuthService` to call directly.
It also preserves a commented-out `InMemoryUserDetailsManager` bean as a historical marker of the
project's pre-database-backed-users iteration.

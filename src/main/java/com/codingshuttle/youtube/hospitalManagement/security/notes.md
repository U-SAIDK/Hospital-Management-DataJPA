# `security/` package notes

## Purpose

This package implements the entire authentication/authorization pipeline for the Hospital
Management System: JWT issuance/verification, a custom `UserDetailsService` backed directly by
the JPA `User` entity, a manual `OncePerRequestFilter` that populates the `SecurityContext` on
every request, OAuth2 login convergence onto the same JWT contract as normal login, and a static
RBAC (role -> permission) mapping used to compute Spring Security authorities.

## Responsibilities

| Class | Responsibility |
|---|---|
| `AuthService` | Orchestrates login, plain signup, and OAuth2 signup/login. `signUpInternal` is the single shared core that creates a `User` + linked `Patient` for both plain and OAuth2 signup. |
| `AuthUtil` | JWT creation (`generateAccessToken`) and parsing (`getUsernameFromToken`) via `jjwt`, HMAC-SHA key from `jwt.secretKey`. Also extracts provider-specific OAuth2 attributes (Google vs GitHub). |
| `CustomUserDetailsService` | Implements `UserDetailsService.loadUserByUsername` by delegating straight to `UserRepository` - no adapter layer, because `User` already implements `UserDetails`. |
| `JwtAuthFilter` | A `OncePerRequestFilter` that reads the `Authorization: Bearer` header on every request, verifies the JWT, loads the `User`, and manually sets `SecurityContextHolder`'s authentication. |
| `OAuth2SuccessHandler` | `AuthenticationSuccessHandler` invoked after a successful OAuth2 provider login; converts the `OAuth2User` into the app's own JWT-based `LoginResponseDto` and writes it directly to the response. |
| `RolePermissionMapping` | Static, immutable `Map<RoleType, Set<PermissionType>>` - the single source of truth for what each role can do. |
| `WebSecurityConfig` | Wires the filter chain: stateless sessions, CSRF disabled, URL-based authorization rules, `JwtAuthFilter` registration, OAuth2 login handlers, and exception routing into `GlobalExceptionHandler`. |

## Package interaction

- `entity/User` implements `UserDetails` directly - the JPA entity IS the security principal.
  `User.getAuthorities()` expands each `RoleType` into fine-grained `PermissionType` authorities
  via `RolePermissionMapping`, plus a coarse `ROLE_x` authority, which is why `WebSecurityConfig`
  can use both `hasRole(...)` and `hasAnyAuthority(...)` in the same config.
- `repository/UserRepository` is the only persistence dependency this package touches directly
  for authentication (`JwtAuthFilter`, `CustomUserDetailsService`, `AuthService` all call it).
- `error/GlobalExceptionHandler` is the landing point for every exception this package's filter
  and handlers can't resolve inline - `JwtAuthFilter`'s catch block, `WebSecurityConfig`'s
  `accessDeniedHandler`, and the OAuth2 `failureHandler` all delegate to it via
  `HandlerExceptionResolver.resolveException(...)`.
- `AdminController` is the only place Doctors get created (`onBoardNewDoctor`); this package
  always creates Patients on self-service signup (`AuthService.signUpInternal`).

## Sequence diagram: normal JWT-secured request flow

```
Client                JwtAuthFilter          AuthUtil            UserRepository      SecurityContextHolder   Controller
  |  GET /doctors/...       |                    |                     |                     |                  |
  |  Authorization: Bearer  |                    |                     |                     |                  |
  |------------------------>|                    |                     |                     |                  |
  |                         | getUsernameFromToken(token)               |                     |                  |
  |                         |------------------->|                     |                     |                  |
  |                         |  verify signature, parse claims           |                     |                  |
  |                         |<-------------------|                     |                     |                  |
  |                         |  findByUsername(username)                |                     |                  |
  |                         |----------------------------------------->|                     |                  |
  |                         |<-----------------------------------------|                     |                  |
  |                         |  new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())        |
  |                         |  setAuthentication(...)                  |                     |                  |
  |                         |------------------------------------------------------------->  |                  |
  |                         |  filterChain.doFilter(request, response)                        |                  |
  |                         |----------------------------------------------------------------------------------->|
  |                         |                    |                     |     (reads principal via SecurityContextHolder)
  |<--------------------------------------------------------------- response ----------------------------------|
```

If the token is missing/invalid/expired, `JwtAuthFilter`'s catch block calls
`handlerExceptionResolver.resolveException(...)`, which routes into `GlobalExceptionHandler`
(e.g. `handleJwtException`) instead of the request ever reaching a controller.

## Sequence diagram: OAuth2 login flow

```
Client        Provider (Google/GitHub)   Spring OAuth2 Login Filter   OAuth2SuccessHandler   AuthService        AuthUtil       UserRepository/PatientRepository
  |  GET /oauth2/authorization/google |                              |                        |                   |                       |
  |----------------------------------->                              |                        |                   |                       |
  |          redirect + consent       |                              |                        |                   |                       |
  |<-----------------------------------|                              |                        |                   |                       |
  |  callback with auth code          |                              |                        |                   |                       |
  |------------------------------------------------------------------>|                        |                   |                       |
  |                                    |  exchanges code, builds OAuth2User, invokes success handler               |                       |
  |                                    |--------------------------->  |                        |                   |                       |
  |                                    |                              |  handleOAuth2LoginRequest(oAuth2User, registrationId)               |
  |                                    |                              |----------------------->|                   |                       |
  |                                    |                              |                        | determineProviderIdFromOAuth2User(...)     |
  |                                    |                              |                        |------------------>|                       |
  |                                    |                              |                        |  findByProviderIdAndProviderType(...)      |
  |                                    |                              |                        |-------------------------------------------->|
  |                                    |                              |                        |  (new user) signUpInternal(...) -> User+Patient saved                     |
  |                                    |                              |                        |-------------------------------------------->|
  |                                    |                              |                        | generateAccessToken(user)                   |
  |                                    |                              |                        |------------------>|                       |
  |                                    |                              |  LoginResponseDto(token, userId)           |                       |
  |                                    |                              |<-----------------------|                   |                       |
  |                                    |                              |  writes JSON directly to HttpServletResponse                        |
  |<-------------------------------------------------------------------------------------------                    |                       |
```

Note the convergence point: regardless of whether login happened via password or OAuth2, the
client receives the exact same `LoginResponseDto` JSON shape, so it never needs to branch on
auth method afterward - every subsequent request just carries the JWT like any other.

## Important annotations/patterns (with real examples)

- **`OncePerRequestFilter`** (`JwtAuthFilter`): guarantees single execution per request even
  across internal forwards/includes - critical since it mutates shared `SecurityContextHolder` state.
- **`HandlerExceptionResolver` delegation** (`JwtAuthFilter.doFilterInternal`'s catch block,
  `WebSecurityConfig`'s `accessDeniedHandler`/OAuth2 `failureHandler`): the filter chain runs
  outside normal MVC dispatch, so exceptions thrown there must be manually routed into
  `GlobalExceptionHandler` via `resolveException(...)` - writing the response directly would
  bypass the app's single error-shape contract.
- **`@Transactional` on multi-entity writes** (`AuthService.handleOAuth2LoginRequest`,
  implicitly relied upon within `signUpInternal`'s User+Patient save chain): keeps the User+Patient
  creation atomic - a failure partway through must not leave one row without the other.
- **Static immutable RBAC map** (`RolePermissionMapping.map`, built with `Map.of(...)`):
  permissions are computed from role at read time (`User.getAuthorities()`), not stored per-user
  in the database - a code change to the map instantly changes behavior for every user of that role.
- **`SessionCreationPolicy.STATELESS`** + **CSRF disabled** (`WebSecurityConfig`): both follow
  from JWT being the sole identity carrier - no server-side session, no session cookie, so no CSRF surface.
- **`addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`**: JWT
  authentication must happen before Spring Security's default username/password filter in the chain
  so a valid bearer token is honored before any other authentication mechanism runs.

## Common mistakes to watch for

1. **Forgetting `SessionCreationPolicy.STATELESS`** in a JWT-based setup - without it, Spring
   Security defaults to creating an `HttpSession`, which silently reintroduces server-side state
   (and a CSRF surface) that a stateless JWT design is supposed to avoid.
2. **Writing directly to `HttpServletResponse` from a filter's catch block** instead of
   delegating to `HandlerExceptionResolver` - this bypasses `GlobalExceptionHandler` entirely and
   produces an inconsistent error body compared to controller-thrown exceptions.
3. **Letting `RolePermissionMapping` and the entity's `RoleType`/`PermissionType` enums drift
   out of sync** - e.g. adding a new `PermissionType` but forgetting to grant it to any role in
   the map means the permission can never actually be granted, and `WebSecurityConfig` rules
   referencing it (`hasAnyAuthority(...)`) will always deny.
4. **Placing `addFilterBefore` after (not before) `UsernamePasswordAuthenticationFilter`** -
   would mean requests are evaluated by the default filter first, defeating the point of
   JWT-based stateless authentication.
5. **Assuming every OAuth2 provider exposes the same attributes** - `AuthUtil` deliberately
   switches on `registrationId` because Google uses `sub` and GitHub uses `id`/`login`; adding a
   new provider without a corresponding `case` will throw `IllegalArgumentException` at login time.

## Best practices demonstrated here

- Centralize the User+Patient creation logic in one method (`signUpInternal`) so plain and
  OAuth2 signup can never silently diverge.
- Keep RBAC as a small, explicit, statically-defined map rather than scattering permission
  checks across code - one place to audit "what can a DOCTOR do."
- Route all security-filter-chain exceptions through the same `GlobalExceptionHandler` other
  controller exceptions use, so clients see one consistent error contract everywhere.
- Converge divergent auth flows (password vs OAuth2) onto one output contract
  (`LoginResponseDto`) as early as possible, so downstream code doesn't need to branch on auth method.

## Interview questions grounded in this project

**Q1: Why does `JwtAuthFilter` check `SecurityContextHolder.getContext().getAuthentication() == null`
before setting a new authentication?**
A: To avoid overwriting an authentication that may have already been established earlier in the
filter chain (for example, by Spring Security's own OAuth2 login filter processing the same
request). This filter should only fill the gap when nothing has already authenticated the request.

**Q2: Why is `signUpInternal` shared between plain signup and OAuth2 signup instead of having
two separate creation methods?**
A: Both flows must produce the identical invariant: a `User` row paired with a `Patient` row,
created together. Sharing one method guarantees that invariant can't drift between the two entry
points - e.g. one implementation forgetting to create the `Patient`, or using different default roles.

**Q3: Why is CSRF disabled in `WebSecurityConfig`?**
A: CSRF protection defends against a browser automatically attaching a session cookie to a
forged cross-site request. This app uses stateless JWTs sent via an explicit `Authorization`
header, which browsers never attach automatically, so there's no ambient credential for CSRF to exploit.

**Q4: What's the difference between `hasRole(ADMIN.name())` and
`hasAnyAuthority(APPOINTMENT_DELETE.name(), USER_MANAGE.name())` used in `WebSecurityConfig`,
and how does `User.getAuthorities()` support both?**
A: `hasRole` checks for a coarse `ROLE_x`-prefixed authority; `hasAnyAuthority` checks fine-grained
permission strings like `patient:read`. `User.getAuthorities()` emits both kinds simultaneously
(role-derived permissions from `RolePermissionMapping` plus a `ROLE_x` authority), which is what
lets the same principal satisfy either style of check.

**Q5: Why does `RolePermissionMapping` compute authorities at read time instead of storing them
on the `User` entity?**
A: Storing permissions per-user would require a data migration every time role capabilities
change, and risks per-user drift. Computing from a static map means a single code change updates
every user of that role instantly - at the cost of not being able to audit or override permissions per individual user.

## Summary

This package's job is to make identity stateless and uniform: whether a user logs in with a
password or through an OAuth2 provider, whether a request is authenticated via
`AuthenticationManager` or via `JwtAuthFilter`'s manual token check, the same `User` JPA entity
ends up as the security principal, the same `RolePermissionMapping` computes its authorities, and
any failure along the way lands in the same `GlobalExceptionHandler` error shape.

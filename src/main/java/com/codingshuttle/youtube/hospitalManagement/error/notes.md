# `error/` package notes

## Purpose

Centralizes error handling for the entire application into one consistent JSON response shape,
so every controller/service/security-filter failure - regardless of its origin - is surfaced to
API clients the same way.

## Responsibilities

| Class | Responsibility |
|---|---|
| `GlobalExceptionHandler` | `@RestControllerAdvice` that intercepts specific exception types (`UsernameNotFoundException`, `AuthenticationException`, `JwtException`, `AccessDeniedException`) plus a generic `Exception` catch-all, mapping each to an appropriate HTTP status and `ApiError` body. |
| `ApiError` | The single response DTO (`timeStamp`, `error`, `statusCode`) returned by every handler above. |

## Package interaction

- Normally, `@RestControllerAdvice` only intercepts exceptions thrown from `@Controller`/
  `@RestController` methods during Spring MVC's normal dispatch.
- This app's `security/JwtAuthFilter` runs as a servlet filter, **outside** that dispatch
  pipeline, so exceptions it throws (e.g. an invalid/expired JWT causing `JwtException`, or a
  missing user causing `UsernameNotFoundException`) would otherwise never reach this class.
  `JwtAuthFilter`'s catch block works around this by calling
  `HandlerExceptionResolver.resolveException(request, response, null, ex)`, which manually feeds
  the exception back into Spring MVC's exception-resolution machinery - landing here anyway.
- `security/WebSecurityConfig`'s `accessDeniedHandler` and the OAuth2 `failureHandler` use the
  exact same `HandlerExceptionResolver` delegation pattern for the same reason - both run inside
  Spring Security's filter chain, not controller methods.
- Any exception from `security/AuthService`, `PatientService`, `DoctorService`, etc. thrown
  during normal controller processing is caught directly by this advice with no extra plumbing needed.

## Sequence diagram: security-filter exception routed here

```
Client        JwtAuthFilter              HandlerExceptionResolver      GlobalExceptionHandler
  |  request with bad/expired JWT |                                   |
  |------------------------------->                                   |
  |                                |  getUsernameFromToken() throws JwtException                |
  |                                |  catch (Exception ex) { ... }     |
  |                                |  resolveException(request, response, null, ex)              |
  |                                |---------------------------------->                          |
  |                                |                                   |  matches @ExceptionHandler(JwtException.class)
  |                                |                                   |----------------------->  |
  |                                |                                   |  builds ApiError(msg, 401)
  |<----------------------------------------------------------------------------------------------|
  |  401 { timeStamp, error, statusCode }                              |                          |
```

## Sequence diagram: normal controller exception

```
Client            Controller              Service               GlobalExceptionHandler
  |  request        |                       |                        |
  |----------------->                       |                        |
  |                  |  calls service method |                       |
  |                  |---------------------->                        |
  |                  |     throws e.g. IllegalArgumentException       |
  |                  |<----------------------|                        |
  |                  |   (unhandled, propagates out of controller)    |
  |                  |------------------------------------------------>
  |                  |                       |   matches @ExceptionHandler(Exception.class) catch-all
  |                  |                       |                        |  builds ApiError(msg, 500)
  |<----------------------------------------------------------------------------
  |  500 { timeStamp, error, statusCode }    |                        |
```

(Note: `AuthService.signUpInternal` throwing `IllegalArgumentException("User already exists")`
is a real example in this codebase of an exception with no dedicated handler - it falls through
to the generic `Exception` handler and returns 500, which is a case worth noticing rather than
assuming every business exception gets a tailored status code.)

## Important annotations/patterns (with real examples)

- **`@RestControllerAdvice`** (`GlobalExceptionHandler`): combines `@ControllerAdvice` +
  `@ResponseBody, applying across every controller in the app without per-controller wiring.
- **Handler specificity ordering**: Spring dispatches to the most specific matching
  `@ExceptionHandler` for a thrown exception's type. In this class, `JwtException` (from `jjwt`,
  unrelated to Spring Security's exception hierarchy) needs its own handler precisely because it
  would otherwise fall through to the generic `Exception` handler and return a misleading 500
  instead of a 401 for what is really an auth failure.
- **Catch-all `@ExceptionHandler(Exception.class)`**: acts as the last line of defense so no
  unhandled exception ever leaks a raw stack trace or container default error page to the client
  - every failure gets the same `ApiError` JSON shape, even ones nobody anticipated.
- **`HandlerExceptionResolver.resolveException(...)`** (used in `JwtAuthFilter` and
  `WebSecurityConfig`, not in this package itself): the bridge that lets code running outside
  MVC dispatch (servlet filters) still land in this `@RestControllerAdvice`.

## Common mistakes to watch for

1. **Assuming `@RestControllerAdvice` alone catches filter-level exceptions** - it doesn't;
   without `JwtAuthFilter`/`WebSecurityConfig` explicitly delegating via
   `HandlerExceptionResolver`, a bad JWT would produce a raw servlet-container error page instead
   of a clean `ApiError` JSON response.
2. **Ordering assumption bugs**: adding a new, more general exception handler before a more
   specific one doesn't actually change Spring's dispatch (it picks the most specific match
   regardless of declaration order) - but relying on declaration order for correctness is a
   common misunderstanding worth being aware of when reading this class.
3. **Not adding a dedicated handler for a new exception type** - as seen with
   `IllegalArgumentException` in `AuthService.signUpInternal`, an exception with no specific
   handler silently becomes a 500 via the generic catch-all, even when a 400 Bad Request would be
   more accurate to the client.
4. **Forgetting the generic `Exception` handler entirely** - without it, any unexpected
   exception (NPE, DB constraint violation, etc.) bypasses this class's consistent JSON shape and
   falls back to the framework's default error handling.

## Best practices demonstrated here

- One error response shape (`ApiError`) for the whole application, regardless of exception origin.
- A layered handler strategy: specific exception types get tailored messages/status codes,
  while a catch-all guarantees nothing ever escapes unhandled.
- Bridging non-MVC exception sources (servlet filters) into the same centralized handler via
  `HandlerExceptionResolver`, rather than duplicating error-formatting logic in the filter itself.

## Interview questions grounded in this project

**Q1: Why does this app need both `@RestControllerAdvice` and manual
`HandlerExceptionResolver.resolveException(...)` calls in `JwtAuthFilter`?**
A: `@RestControllerAdvice` only intercepts exceptions during normal Spring MVC dispatch (i.e.
inside controller method execution). `JwtAuthFilter` is a servlet filter that runs before
`DispatcherServlet`, so exceptions thrown there never reach that dispatch cycle unless explicitly
routed back into it via `HandlerExceptionResolver` - which is exactly what its catch block does.

**Q2: Why does `JwtException` get its own `@ExceptionHandler` instead of relying on the generic
`Exception` handler?**
A: `JwtException` comes from the `jjwt` library and isn't a subtype of Spring Security's
`AuthenticationException`, so without a dedicated handler it would fall through to the catch-all
and return a 500 Internal Server Error - misleading for what is actually a 401-worthy "invalid
token" situation. The dedicated handler ensures accurate status codes for expired/tampered tokens.

**Q3: What HTTP status does `AuthService.signUpInternal`'s `IllegalArgumentException("User
already exists")` produce today, and is that ideal?**
A: It falls through to the generic `@ExceptionHandler(Exception.class)` and returns 500 Internal
Server Error, even though "user already exists" is really a 409 Conflict or 400 Bad Request
client error. This is a good example of why the ordering/coverage of specific handlers matters -
a missing dedicated handler silently mislabels a client error as a server error.

**Q4: Why is `ApiError` a single shared class instead of each handler building its own ad hoc
response body?**
A: Consistency - every consumer of this API (frontend, mobile client, other services) can rely on
exactly one shape (`timeStamp`, `error`, `statusCode`) no matter which of the five handlers fired,
simplifying client-side error handling code.

**Q5: If you added a new custom exception (say, `AppointmentConflictException`), where would you
add handling, and would ordering in the class matter?**
A: Add a new `@ExceptionHandler(AppointmentConflictException.class)` method anywhere in the
class - Spring resolves handlers by most-specific exception type match at dispatch time, not by
declaration order, so placement in the file doesn't affect behavior. What matters is that a
specific handler exists at all; otherwise it silently falls to the generic `Exception` handler as a 500.

## Summary

This package is the app's single funnel for turning any failure - business-rule violations,
authentication problems, expired JWTs, insufficient permissions, or truly unexpected errors -
into one predictable JSON error contract (`ApiError`), reachable both from normal controller code
via `@RestControllerAdvice` and from the security filter chain via manual
`HandlerExceptionResolver` delegation.

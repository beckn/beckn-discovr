# ADR-0011: Constructor Injection Only — No @Autowired Field Injection

**Date**: 2026-05-26
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

Spring Boot supports three dependency injection styles: field injection (`@Autowired` on a field), setter injection, and constructor injection. Field injection is the most concise but makes dependencies invisible in tests (they require reflection to set), prevents `final` fields, and hides circular dependency issues until runtime. Constructor injection exposes all dependencies explicitly and enables testability without a Spring context.

## Decision

All Spring-managed beans use constructor injection exclusively. Dependencies are declared as `private final` fields and set in a single constructor. `@Autowired` is not used on fields or setters anywhere in the codebase. Where Spring requires a no-arg constructor (e.g., JPA entities), the field injection rule does not apply — those are not Spring beans.

## Alternatives Considered

### Alternative 1: Field injection with @Autowired
- **Pros**: Less boilerplate — no constructor to write; Spring handles wiring automatically
- **Cons**: Dependencies are hidden; cannot use `final` fields; circular dependencies fail at runtime (not compile time); unit tests must use `ReflectionTestUtils` to inject mocks
- **Why not**: Hidden dependencies make code harder to reason about; `final` fields make threading contracts explicit

### Alternative 2: Mix of constructor and field injection
- **Pros**: Constructor for "important" dependencies, field for "optional" ones
- **Cons**: Inconsistency makes code harder to review; no clear rule for which injection style to use
- **Why not**: A consistent rule is easier to enforce in code review; mixing styles provides no practical benefit

## Consequences

### Positive
- All dependencies are visible in the constructor signature — new developers can immediately see what a service depends on
- `final` fields guarantee that dependencies are set exactly once and never mutated
- Unit tests can instantiate beans directly with mock dependencies — no Spring context needed for unit tests
- Circular dependencies fail at application startup, not silently at first use

### Negative
- Verbose constructors for services with many dependencies (e.g., `DiscoveryService` has 8+ parameters)
- Lombok `@RequiredArgsConstructor` could reduce boilerplate but is not currently used, keeping the dependency explicit

### Risks
- None significant. This is a well-established Spring best practice.

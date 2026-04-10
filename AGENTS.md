# AGENTS.md

## Project summary

This repository is a Kotlin 1.9 / Spring Boot 3.2 / Java 17 backend for managing equipment inventory and equipment allocation requests.

Main capabilities:
- register equipment
- list equipment, optionally filtered by state
- retire equipment
- create allocation requests based on equipment policy requirements
- confirm or cancel allocations

This is **not** an employee CRUD application. `employeeId` exists only as a UUID carried by allocation requests.

## Architecture

The code follows a ports-and-adapters / hexagonal style:

- `adapter/in/web` - REST controllers, request/response DTOs, web mappers, exception handler
- `domain/model` - domain state and core models
- `domain/service` - business rules and allocation workflow
- `domain/port/in` - use case interfaces
- `domain/port/out` - repository ports
- `adapter/out/persistence` - JPA entities, Spring Data repositories, persistence adapters, entity mappers
- `config` - Spring configuration

## Trust order

When documentation and code differ, trust sources in this order:

1. tests in `src/test/kotlin`
2. controllers and DTOs in `adapter/in/web`
3. domain services in `domain/service`
4. ports and persistence adapters
5. `README.md`

The historical README was partially outdated, so always verify behavior in code and tests before changing contracts.

## Actual HTTP API

Do not assume `/api/...` prefixes.

### Equipment
- `POST /equipments`
- `GET /equipments`
- `POST /equipments/{id}/retire`

### Allocations
- `POST /allocations`
- `GET /allocations/{id}`
- `POST /allocations/{id}/confirm`
- `POST /allocations/{id}/cancel`

## Core domain rules

### Equipment rules
Implemented mainly in `domain/service/EquipmentService.kt`.

- New equipment starts in `EquipmentState.AVAILABLE`
- `conditionScore` must be in `[0.0, 1.0]`
- `brand` and `model` must not be blank
- Equipment can be retired only when currently `AVAILABLE`
- Retirement reason must not be blank

### Equipment states
- `AVAILABLE`
- `RESERVED`
- `ASSIGNED`
- `RETIRED`

### Allocation rules
Implemented mainly in `domain/service/AllocationService.kt` and `domain/service/AllocationProcessor.kt`.

- Allocation policy must not be empty
- Each requirement must have `quantity > 0`
- `minimumConditionScore`, if present, must be in `[0.0, 1.0]`
- If matching equipment cannot be found, allocation becomes `FAILED`
- Successful processing reserves selected equipment by moving it to `RESERVED`
- Confirm moves reserved equipment to `ASSIGNED`
- Cancel may release reserved equipment back to `AVAILABLE`

### Allocation states
- `PENDING`
- `ALLOCATED`
- `FAILED`
- `CONFIRMED`
- `CANCELLED`

## Allocation processing caveat

Be careful around allocation execution flow.

`AllocationService.createAllocation()` currently does both:
- publishes `AllocationCreatedEvent`
- directly calls `allocationProcessor.processAllocation(allocation.id)`

`AllocationEventHandler` also listens after commit and calls `allocationProcessor.processAllocation(...)` again.

This is mostly safe because `AllocationProcessor` only acts on allocations in `PENDING`, but any refactor in this area must preserve idempotent behavior and avoid double-processing bugs.

## Allocation algorithm behavior

`domain/service/AllocationAlgorithm.kt`:
- applies hard constraints on equipment `type`
- applies hard constraints on `minimumConditionScore`
- treats `preferredBrand` as a soft preference with a strong score bonus
- also prefers newer purchase dates and better condition scores
- searches for a globally feasible combination, not just greedy local matches

If you change allocation behavior, update algorithm tests and service/integration tests together.

## Error handling contract

`adapter/in/web/GlobalExceptionHandler.kt` maps exceptions as follows:
- `BadRequestException` -> HTTP 400
- `NotFoundException` -> HTTP 404
- `ConflictException` -> HTTP 409
- Bean validation errors -> HTTP 400

Preserve this behavior unless the task explicitly changes the API contract.

## Files to inspect before changes

### For equipment-related tasks
- `src/main/kotlin/com/tequipy/challenge/adapter/in/web/EquipmentController.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/EquipmentService.kt`
- `src/main/kotlin/com/tequipy/challenge/adapter/in/web/dto/EquipmentRequest.kt`
- `src/main/kotlin/com/tequipy/challenge/adapter/in/web/dto/RetireEquipmentRequest.kt`
- `src/test/kotlin/com/tequipy/challenge/adapter/in/web/EquipmentControllerIntegrationTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/EquipmentServiceTest.kt`

### For allocation-related tasks
- `src/main/kotlin/com/tequipy/challenge/adapter/in/web/AllocationController.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/AllocationService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/AllocationProcessor.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/AllocationEventHandler.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/AllocationAlgorithm.kt`
- `src/test/kotlin/com/tequipy/challenge/adapter/in/web/AllocationControllerIntegrationTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/AllocationServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/AllocationAlgorithmTest.kt`

### For persistence changes
- `src/main/kotlin/com/tequipy/challenge/adapter/out/persistence/adapter/*`
- `src/main/kotlin/com/tequipy/challenge/adapter/out/persistence/entity/*`
- `src/main/kotlin/com/tequipy/challenge/adapter/out/persistence/mapper/*`
- `src/main/kotlin/com/tequipy/challenge/adapter/out/persistence/repository/*`

## Testing guidance

Prefer targeted tests for the area you touched.

On Windows PowerShell, use:

```powershell
Set-Location "C:\Users\48502\IdeaProjects\tequipy-code-challenge"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat cleanTest test --tests "com.tequipy.challenge.domain.service.*" --no-daemon
```

Integration tests use Spring Boot + Testcontainers + PostgreSQL, so Docker availability may be required.

## Common pitfalls

- Do not implement against outdated employee endpoints from older documentation
- Do not introduce `/api` prefixes unless explicitly requested
- Do not assume there is an `Employee` aggregate in the codebase
- Preserve enum states and state transition rules
- Keep DTO validation annotations aligned with domain validation
- Use tests as the behavioral contract


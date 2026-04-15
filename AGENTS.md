# AGENTS.md

## Project summary

This repository is a Kotlin 1.9 / Spring Boot 3.2 / Java 17 backend for managing equipment inventory and equipment allocation requests.

Main capabilities:
- register equipment
- list equipment, optionally filtered by state
- retire equipment
- create allocation requests based on equipment policy requirements
- confirm or cancel allocations

This is **not** an employee CRUD application. `employeeId` only appears as a UUID carried by allocation requests/messages.

## Architecture

The code follows a ports-and-adapters / hexagonal style, but the actual package names in this repo are:

- `adapter/api/web` - REST controllers, request/response DTOs, web mappers, exception handler
- `adapter/api/messaging` - RabbitMQ listeners and message DTOs
- `adapter/spi/persistence` - JDBC repositories, persistence adapters, entity mappers
- `adapter/spi/messaging` - RabbitMQ publisher
- `domain/model` - domain state and core models
- `domain/command` - application command objects
- `domain/service` - application services and business rules
- `domain/port/api` - use case interfaces
- `domain/port/spi` - repository / messaging / inventory ports
- `config` - Spring configuration, including async/scheduling support and RabbitMQ queues / listener container factories

## Trust order

When documentation and code differ, trust sources in this order:

1. tests in `src/test/kotlin`
2. controllers and DTOs in `adapter/api/web`
3. messaging adapters in `adapter/api/messaging`
4. application/domain services in `domain/service`
5. ports and persistence adapters
6. `README.md`

The historical README can lag behind the code, so always verify behavior in code and tests before changing contracts.

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

## Key ports and commands

### Equipment use cases
- `RegisterEquipmentUseCase`
- `GetEquipmentUseCase`
- `ListEquipmentUseCase`
- `RetireEquipmentUseCase`

### Allocation use cases
- `CreateAllocationUseCase`
- `GetAllocationUseCase`
- `ConfirmAllocationUseCase`
- `CancelAllocationUseCase`
- `ProcessAllocationUseCase`
- `CompleteAllocationUseCase`

### Command objects
- `domain/command/ProcessAllocationCommand.kt` and `domain/command/CompleteAllocationCommand.kt` are the current commands used by messaging/application flow.

## Core domain rules

### Equipment rules
Implemented mainly in `domain/service/RegisterEquipmentService.kt` and `domain/service/RetireEquipmentService.kt`.

- new equipment starts in `EquipmentState.AVAILABLE`
- `conditionScore` must be in `[0.0, 1.0]`
- `brand` and `model` must not be blank
- equipment can be retired only when currently `AVAILABLE`
- retirement reason must not be blank

### Equipment states
- `AVAILABLE`
- `RESERVED`
- `ASSIGNED`
- `RETIRED`

### Allocation rules
Implemented mainly in `domain/service/CreateAllocationService.kt`, `domain/service/ProcessAllocationService.kt`, `domain/service/ConfirmAllocationService.kt`, and `domain/service/CancelAllocationService.kt`.

- allocation policy must not be empty
- each requirement must have `quantity > 0`
- `minimumConditionScore`, if present, must be in `[0.0, 1.0]`
- if matching equipment cannot be found, allocation becomes `FAILED`
- successful processing reserves selected equipment by moving it to `RESERVED`
- confirm moves reserved equipment to `ASSIGNED`
- cancel may release reserved equipment back to `AVAILABLE`

### Allocation states
- `PENDING`
- `ALLOCATED`
- `FAILED`
- `CONFIRMED`
- `CANCELLED`

## Allocation processing flow

Be careful around allocation execution flow.

Current flow is:

1. `CreateAllocationService` validates policy, saves a `PENDING` allocation, and publishes the created message through `AllocationEventPublisher`.
2. `RabbitMQAllocationEventPublisher` publishes `AllocationRequestedMessage` to `allocation.queue` **after transaction commit**.
3. `AllocationMessageListener` maps `AllocationRequestedMessage` to `domain.command.ProcessAllocationCommand` and submits it to `BatchAllocationCollector`.
4. `BatchAllocationCollector` buffers commands in memory and flushes them to `BatchAllocationService` when the batch reaches `MAX_BATCH_SIZE` or when the scheduled `tequipy.batch-allocation.window-ms` delay expires.
5. `BatchAllocationService` is idempotent through `AllocationProcessingRepository`, uses `InventoryAllocationPort` to select and reserve equipment in one batch transaction, stores the result, and republishes an `EquipmentAllocated` batch message (containing `AllocationProcessedResult` items) to `allocation.result.queue`.
6. `AllocationProcessedMessageListener` consumes `allocation.result.queue` with a dedicated listener container and applies the final `ALLOCATED` / `FAILED` state to the allocation row through `CompleteAllocationUseCase` only if it is still `PENDING`.

Important caveat:
- duplicate allocation processing is expected and must remain safe
- `AllocationLockContentionException` is used to trigger Rabbit retry and eventual DLQ routing
- do not break idempotency in `ProcessAllocationUseCase`, `BatchAllocationService`, or `AllocationProcessingRepository`

## Allocation algorithm behavior

`domain/service/AllocationAlgorithm.kt`:
- applies hard constraints on equipment `type`
- applies hard constraints on `minimumConditionScore`
- treats `preferredBrand` as a soft preference with a strong score bonus
- also prefers newer purchase dates and better condition scores
- searches for a globally feasible combination, not just greedy local matches

If you change allocation behavior, update algorithm tests and service/integration tests together.

## Error handling contract

`adapter/api/web/GlobalExceptionHandler.kt` maps exceptions as follows:
- `BadRequestException` -> HTTP 400
- `NotFoundException` -> HTTP 404
- `ConflictException` -> HTTP 409
- bean validation errors -> HTTP 400

Preserve this behavior unless the task explicitly changes the API contract.

## Files to inspect before changes

### For equipment-related tasks
- `src/main/kotlin/com/tequipy/challenge/adapter/api/web/EquipmentController.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/RegisterEquipmentService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/GetEquipmentService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/ListEquipmentService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/RetireEquipmentService.kt`
- `src/main/kotlin/com/tequipy/challenge/adapter/api/web/dto/EquipmentRequest.kt`
- `src/main/kotlin/com/tequipy/challenge/adapter/api/web/dto/RetireEquipmentRequest.kt`
- `src/test/kotlin/com/tequipy/challenge/adapter/api/web/EquipmentControllerIntegrationTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/RegisterEquipmentServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/GetEquipmentServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/ListEquipmentServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/RetireEquipmentServiceTest.kt`

### For allocation-related tasks
- `src/main/kotlin/com/tequipy/challenge/adapter/api/web/AllocationController.kt`
- `src/main/kotlin/com/tequipy/challenge/adapter/api/messaging/AllocationMessageListener.kt`
- `src/main/kotlin/com/tequipy/challenge/adapter/api/messaging/AllocationProcessedMessageListener.kt`
- `src/main/kotlin/com/tequipy/challenge/config/AsyncConfig.kt`
- `src/main/kotlin/com/tequipy/challenge/config/RabbitMQConfig.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/CreateAllocationService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/ProcessAllocationService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/BatchAllocationCollector.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/BatchAllocationService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/BatchAllocationMetrics.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/ConfirmAllocationService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/CancelAllocationService.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/AllocationAlgorithm.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/command/ProcessAllocationCommand.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/command/CompleteAllocationCommand.kt`
- `src/test/kotlin/com/tequipy/challenge/adapter/api/web/AllocationControllerIntegrationTest.kt`
- `src/test/kotlin/com/tequipy/challenge/adapter/api/messaging/AllocationMessageListenerTest.kt`
- `src/test/kotlin/com/tequipy/challenge/adapter/api/messaging/AllocationProcessedMessageListenerTest.kt`
- `src/test/kotlin/com/tequipy/challenge/adapter/api/messaging/AllocationRetryIntegrationTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/CreateAllocationServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/ProcessAllocationServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/BatchAllocationServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/ConfirmAllocationServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/CancelAllocationServiceTest.kt`
- `src/test/kotlin/com/tequipy/challenge/domain/service/AllocationAlgorithmTest.kt`

### For inventory / persistence changes
- `src/main/kotlin/com/tequipy/challenge/domain/port/spi/InventoryAllocationPort.kt`
- `src/main/kotlin/com/tequipy/challenge/domain/service/InventoryAllocationService.kt`
- `src/main/kotlin/com/tequipy/challenge/adapter/spi/persistence/adapter/*`
- `src/main/kotlin/com/tequipy/challenge/adapter/spi/persistence/repository/*`
- `src/main/kotlin/com/tequipy/challenge/adapter/spi/messaging/RabbitMQAllocationEventPublisher.kt`

## Testing guidance

Prefer targeted tests for the area you touched.

On Windows PowerShell, use:

```powershell
Set-Location "C:\Users\48502\IdeaProjects\tequipy-code-challenge"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat cleanTest test --tests "com.tequipy.challenge.domain.service.*" --no-daemon
```

For allocation batching or messaging changes, the most useful targeted tests are `BatchAllocationServiceTest`, `AllocationMessageListenerTest`, `AllocationProcessedMessageListenerTest`, and `AllocationRetryIntegrationTest`.

Integration tests use Spring Boot + Testcontainers + PostgreSQL + RabbitMQ, so Docker availability may be required.

## Common pitfalls

- Do not implement against outdated employee endpoints from older documentation
- Do not introduce `/api` prefixes unless explicitly requested
- Do not assume there is an `Employee` aggregate in the codebase
- Preserve enum states and state transition rules
- Keep DTO validation annotations aligned with domain validation
- Keep messaging idempotent; duplicate Rabbit deliveries are expected
- Use tests as the behavioral contract


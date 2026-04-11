# Tequipy Code Challenge

A Kotlin Spring Boot REST API for managing equipment inventory and equipment allocation requests, following **hexagonal architecture** principles.

## Current Scope

The application currently supports:

- registering equipment
- listing equipment, optionally filtered by state
- retiring equipment
- creating allocation requests based on policy requirements
- confirming or cancelling allocations

## Technology Stack

- **Language**: Kotlin 1.9.22
- **Framework**: Spring Boot 3.2.3
- **Java**: 17
- **Database**: PostgreSQL with Spring JDBC (JdbcTemplate); schema managed by Flyway
- **Messaging**: RabbitMQ (async allocation processing via AMQP)
- **Build Tool**: Gradle 8.6
- **Validation**: Jakarta Bean Validation
- **Testing**: JUnit 5, MockK, Testcontainers (requires Docker)
- **Containerization / Deployment**: Docker, Kubernetes manifests in `k8s/`

## Architecture

The application follows a **Hexagonal Architecture** (Ports & Adapters) structure:

```
src/main/kotlin/com/tequipy/challenge/
├── domain/
│   ├── event/               # Domain events
│   ├── model/               # Core domain models and enums
│   ├── port/
│   │   ├── in/              # Use case interfaces
│   │   └── out/             # Repository and event-publisher ports
│   └── service/             # Business logic and allocation workflow
├── adapter/
│   ├── api/
│   │   ├── web/             # REST controllers, DTOs, web mappers, exception handler
│   │   └── messaging/       # RabbitMQ message listener and message DTOs
│   └── spi/
│       ├── persistence/     # JDBC repositories, persistence adapters, entity mappers
│       └── messaging/       # RabbitMQ event publisher
└── config/                  # Spring configuration (RabbitMQ, OpenAPI, Async)
```

## Domain Model Overview

### Equipment

Equipment contains:
- `id`
- `type`
- `brand`
- `model`
- `state`
- `conditionScore`
- `purchaseDate`
- `retiredReason`

#### Equipment states
- `AVAILABLE`
- `RESERVED`
- `ASSIGNED`
- `RETIRED`

#### Equipment types
- `MAIN_COMPUTER`
- `MONITOR`
- `KEYBOARD`
- `MOUSE`

### Allocation request

Allocation requests contain:
- `id`
- `policy`
- `state`
- `allocatedEquipmentIds`

#### Allocation states
- `PENDING`
- `ALLOCATED`
- `FAILED`
- `CONFIRMED`
- `CANCELLED`

## Business Rules

### Equipment rules
- new equipment starts in `AVAILABLE`
- `conditionScore` must be between `0.0` and `1.0`
- `brand` and `model` must not be blank
- equipment can be retired only when it is `AVAILABLE`
- retirement reason must not be blank

### Allocation rules
- allocation policy must not be empty
- each policy item must have `quantity > 0`
- `minimumConditionScore`, if provided, must be between `0.0` and `1.0`
- successful processing moves selected equipment to `RESERVED`
- confirming an allocation moves reserved equipment to `ASSIGNED`
- cancelling an allocation may release reserved equipment back to `AVAILABLE`

## API Endpoints

The actual HTTP API exposed by the application is:

### Equipment
| Method | Path | Description |
|--------|------|-------------|
| POST | `/equipments` | Register new equipment |
| GET | `/equipments` | List all equipment |
| GET | `/equipments?state=RETIRED` | List equipment filtered by state |
| POST | `/equipments/{id}/retire` | Retire available equipment |

### Allocations
| Method | Path | Description |
|--------|------|-------------|
| POST | `/allocations` | Create an allocation request |
| GET | `/allocations/{id}` | Get allocation by id |
| POST | `/allocations/{id}/confirm` | Confirm allocated equipment |
| POST | `/allocations/{id}/cancel` | Cancel allocation |

## Example Payloads

### Register equipment

```json
{
  "type": "MAIN_COMPUTER",
  "brand": "Apple",
  "model": "MacBook Pro",
  "conditionScore": 0.95,
  "purchaseDate": "2025-01-10"
}
```

### Retire equipment

```json
{
  "reason": "Panel damaged"
}
```

### Create allocation

```json
{
  "policy": [
	{
	  "type": "MAIN_COMPUTER",
	  "quantity": 1,
	  "minimumConditionScore": 0.8,
	  "preferredBrand": "Apple"
	},
	{
	  "type": "MONITOR",
	  "quantity": 1
	}
  ]
}
```

## Allocation Processing

Allocation creation publishes a message to the RabbitMQ `allocation.queue`. The listener:

1. receives the pending allocation message
2. selects eligible `AVAILABLE` equipment using `AllocationAlgorithm`
3. marks matching equipment as `RESERVED`
4. changes the allocation state to `ALLOCATED`

If no valid combination exists, the allocation is marked as `FAILED`. Lock contention during concurrent processing triggers automatic retries (up to 12 attempts); messages that exhaust all retries are moved to the dead-letter queue (`allocation.dlq`).

The algorithm:
- applies hard constraints for type and minimum condition score
- treats preferred brand as a strong soft preference
- also prefers newer purchase date and better condition score
- searches for a globally feasible combination across requested slots

## Error Handling

`GlobalExceptionHandler` maps domain and validation errors to HTTP responses:

| Condition | Status |
|-----------|--------|
| validation failure | `400 Bad Request` |
| invalid business input | `400 Bad Request` |
| missing resource | `404 Not Found` |
| invalid state transition / conflict | `409 Conflict` |

## Running Locally

### Prerequisites
- JDK 17+
- PostgreSQL running on `localhost:5432` with database `tequipy`
- RabbitMQ running on `localhost:5672`

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `tequipy` | Database name |
| `DB_USERNAME` | `tequipy` | Database username |
| `DB_PASSWORD` | `tequipy` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ username |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ password |
| `APP_PORT` | `8080` | Application port |

### Build and run

```bash
./gradlew build
./gradlew bootRun
```

## Testing

The project includes:

- **unit tests** for domain services and the allocation algorithm using MockK
- **integration tests** for REST controllers using `@SpringBootTest` and PostgreSQL via Testcontainers

### Useful commands

```bash
./gradlew cleanTest test
```

Run only domain/service tests:

```bash
./gradlew cleanTest test --tests "com.tequipy.challenge.domain.service.*"
```

Run only controller/integration tests:

```bash
./gradlew test --tests "com.tequipy.challenge.adapter.api.web.*"
```

> Integration tests require Docker/Testcontainers support.

## Docker and Kubernetes

The repository includes:
- a `Dockerfile` for building a runnable application image
- Kubernetes manifests in `k8s/` for application and PostgreSQL deployment

Apply manifests with:

```bash
kubectl apply -f k8s/
```

## Notes for Contributors

- Treat tests as the primary behavioral contract
- Prefer updating tests alongside domain logic changes
- Be careful when refactoring allocation flow: processing is triggered by the RabbitMQ message listener after transaction commit; `AllocationProcessor` is idempotent and only acts on allocations in `PENDING` state, so duplicate message delivery is handled safely
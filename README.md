# Tequipy Code Challenge

A Kotlin Spring Boot REST API application for managing employee equipment, following **hexagonal architecture** principles.

## Technology Stack

- **Language**: Kotlin 1.9.22
- **Framework**: Spring Boot 3.2.3
- **Database**: PostgreSQL (JPA/Hibernate)
- **Build Tool**: Gradle 8.6
- **Containerization**: Kubernetes
- **Testing**: JUnit 5, MockK, Testcontainers

## Architecture

The application follows the **Hexagonal Architecture** (Ports & Adapters) pattern:

```
src/main/kotlin/com/tequipy/challenge/
├── domain/
│   ├── model/               # Domain entities (Employee, Equipment)
│   ├── port/
│   │   ├── in/              # Input ports (use case interfaces)
│   │   └── out/             # Output ports (repository interfaces)
│   └── service/             # Domain services (business logic)
├── adapter/
│   ├── in/
│   │   └── web/             # REST controllers, DTOs, mappers
│   └── out/
│       └── persistence/     # JPA entities, repositories, adapters
└── config/                  # Spring configuration
```

## API Endpoints

### Employees (`/api/employees`)
| Method | Path | Description |
|--------|------|-------------|
| POST   | `/api/employees` | Create a new employee |
| GET    | `/api/employees` | Get all employees |
| GET    | `/api/employees/{id}` | Get employee by ID |
| PUT    | `/api/employees/{id}` | Update employee |
| DELETE | `/api/employees/{id}` | Delete employee |

### Equipment (`/api/equipment`)
| Method | Path | Description |
|--------|------|-------------|
| POST   | `/api/equipment` | Create new equipment |
| GET    | `/api/equipment` | Get all equipment |
| GET    | `/api/equipment/{id}` | Get equipment by ID |
| PUT    | `/api/equipment/{id}` | Update equipment |
| DELETE | `/api/equipment/{id}` | Delete equipment |
| PUT    | `/api/equipment/{id}/assign/{employeeId}` | Assign equipment to employee |
| PUT    | `/api/equipment/{id}/unassign` | Unassign equipment from employee |

## Running Locally

### Prerequisites
- JDK 17+
- PostgreSQL running on `localhost:5432` with database `tequipy`

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Start application
./gradlew bootRun
```

### Environment Variables
| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `tequipy` | Database name |
| `DB_USERNAME` | `tequipy` | Database username |
| `DB_PASSWORD` | `tequipy` | Database password |
| `APP_PORT` | `8080` | Application port |

## Kubernetes Deployment

```bash
# Apply all manifests
kubectl apply -f k8s/

# Or apply individually
kubectl apply -f k8s/postgres-pvc.yaml
kubectl apply -f k8s/postgres-deployment.yaml
kubectl apply -f k8s/postgres-service.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

## Testing

The project includes both unit and integration tests:

- **Unit Tests** (`domain/service/`): Test domain services in isolation using MockK mocks
- **Integration Tests** (`adapter/in/web/`): Full stack tests using `@SpringBootTest` with a real PostgreSQL instance via Testcontainers

```bash
# Run all tests
./gradlew test

# Run only unit tests
./gradlew test --tests "com.tequipy.challenge.domain.*"

# Run only integration tests
./gradlew test --tests "com.tequipy.challenge.adapter.*"
```
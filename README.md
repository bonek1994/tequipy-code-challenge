# Tequipy Code Challenge

A Kotlin / Spring Boot REST API for managing equipment inventory and allocation requests, following **hexagonal architecture** principles.

> **📖 API Reference:** full OpenAPI 3.0 specification is available in [`docs/openapi.yaml`](docs/openapi.yaml).
> When running locally the interactive Swagger UI is served at
> [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

---

## Table of Contents

1. [Technology Stack](#technology-stack)
2. [Hexagonal Architecture](#hexagonal-architecture)
3. [Domain Model](#domain-model)
4. [Allocation Algorithm](#allocation-algorithm)
5. [Why Naïve Parallel Consumers Don't Work](#why-naïve-parallel-consumers-dont-work)
6. [Batch Allocation — The Solution](#batch-allocation--the-solution)
7. [Microservice Split Readiness](#microservice-split-readiness)
8. [API Endpoints](#api-endpoints)
9. [Running with Docker Compose](#running-with-docker-compose)
10. [Running on Kubernetes](#running-on-kubernetes)
11. [Running Locally (bare-metal)](#running-locally-bare-metal)
12. [Testing](#testing)
13. [Performance Tests & Reports](#performance-tests--reports)
14. [CI / CD](#ci--cd)

---

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9.22 |
| Framework | Spring Boot 3.2.3 |
| JDK | 17 (Temurin) |
| Database | PostgreSQL 15, Spring JDBC (`JdbcTemplate`), Flyway migrations |
| Messaging | RabbitMQ 3.12 (AMQP, async allocation processing) |
| Build | Gradle 8.6 |
| Validation | Jakarta Bean Validation |
| Testing | JUnit 5, MockK, Testcontainers, Awaitility |
| Containerisation | Docker multi-stage build, Kubernetes manifests |
| CI | GitHub Actions |

---

## Hexagonal Architecture

The codebase follows the **Ports & Adapters** (hexagonal) pattern. The domain layer has
zero dependencies on frameworks or infrastructure — all I/O crosses explicit port
interfaces that are implemented by adapters.

```
src/main/kotlin/com/tequipy/challenge/
├── domain/                          # ← Pure business logic (inner hexagon)
│   ├── model/                       #    Core domain models, enums, value objects
│   ├── command/                     #    Application command objects
│   ├── event/                       #    Domain events
│   ├── port/
│   │   ├── api/                     #    Inbound ports  (use case interfaces)
│   │   └── spi/                     #    Outbound ports (repository, messaging)
│   └── service/                     #    Use case implementations, allocation algorithm
│
├── adapter/                         # ← Infrastructure (outer hexagon)
│   ├── api/
│   │   ├── web/                     #    REST controllers, DTOs, exception handler
│   │   └── messaging/               #    RabbitMQ listeners (inbound adapter)
│   └── spi/
│       ├── persistence/             #    JDBC repositories, entity mappers
│       └── messaging/               #    RabbitMQ publisher  (outbound adapter)
│
└── config/                          # Spring @Configuration classes
```

### Why hexagonal?

| Benefit | How it manifests |
|---------|-----------------|
| **Testability** | Domain services are tested with MockK — no Spring context, no database. |
| **Swappability** | Replacing PostgreSQL or RabbitMQ means writing a new adapter; domain code is untouched. |
| **Clarity of intent** | Use case interfaces (`RegisterEquipmentUseCase`, `CreateAllocationUseCase`, …) form a self-documenting API for the domain. |
| **Dependency rule** | Adapters depend on ports; the domain never imports adapter packages. |

---

## Domain Model

### Equipment

| Field | Description |
|-------|-------------|
| `id` | UUID |
| `type` | `MAIN_COMPUTER`, `MONITOR`, `KEYBOARD`, `MOUSE` |
| `brand` / `model` | Non-blank strings |
| `conditionScore` | `[0.0, 1.0]` |
| `purchaseDate` | `LocalDate` |
| `state` | `AVAILABLE` → `RESERVED` → `ASSIGNED` / `RETIRED` |

### Allocation Request

| Field | Description |
|-------|-------------|
| `id` | UUID |
| `policy` | List of `EquipmentPolicyRequirement` |
| `state` | `PENDING` → `ALLOCATED` → `CONFIRMED` / `CANCELLED` / `FAILED` |
| `allocatedEquipmentIds` | UUIDs of equipment selected by the algorithm |

---

## Allocation Algorithm

> Source: [`domain/service/AllocationAlgorithm.kt`](src/main/kotlin/com/tequipy/challenge/domain/service/AllocationAlgorithm.kt)

### Algorithmic Approach

The algorithm uses a **greedy per-slot** approach with **most-constrained-first** ordering
to assign available equipment to a list of policy requirements (slots).

**Step-by-step:**

1. **Expand slots** — each policy requirement with `quantity = N` is expanded into N
   individual "slots", each requiring exactly 1 item.

2. **Hard-constraint filtering** — for every slot, candidates are filtered by
   `type` (exact match) and `minimumConditionScore` (≥ threshold). Items that fail
   either constraint are eliminated. Only `AVAILABLE` equipment is considered.

3. **Top-K pruning** — candidates for each slot are sorted by the scoring function and
   only the top `groupSize × CANDIDATE_MULTIPLIER` (default multiplier = **3**) are
   retained. `groupSize` is the number of slots sharing the same constraint key
   `(type, minimumConditionScore)`, so a request for 5 monitors keeps 5 × 3 = 15
   candidates per slot.

4. **Most-constrained-first ordering** — slots are sorted by ascending candidate-list
   size so the most tightly constrained slots are processed first, reducing the chance
   of a later slot finding no available candidate.

5. **Greedy selection** — for each slot (in most-constrained-first order), the
   highest-scoring candidate not yet assigned to another slot is selected. If no
   candidate is available for any slot, the allocation returns `null` (failed).

6. **Scoring function** — each candidate is scored as:
   ```
   score = brandBonus + conditionScore + recencyScore
   ```
   - `brandBonus = 10.0` if the candidate's brand matches the slot's `preferredBrand`
     (case-insensitive), otherwise `0.0`. The large bonus makes brand a **strong soft
     preference** without being a hard requirement.
   - `conditionScore` is the equipment's raw condition value in `[0.0, 1.0]`.
   - `recencyScore` is the equipment's normalized purchase date in `[0.0, 1.0]`, where
     `0.0` is the oldest item in the eligible pool and `1.0` is the newest. When all
     items share the same purchase date, every item receives `0.0`. Recency acts as a
     **tiebreaker**: it can only swing a decision when two candidates are otherwise
     equal (or very close) on brand preference and condition.

### Time Complexity

| Variable | Meaning |
|----------|---------|
| S | Total number of slots (Σ quantity across all requirements) |
| K | `CANDIDATE_MULTIPLIER` (default 3) |
| G | Number of slots sharing the same constraint key (type, minimumConditionScore) |

- **Worst-case:** O(S · G·K) — for each slot, scan up to G·K candidates to find the
  first unused one. In practice this is very fast because:
  - Typical S ≤ 4 (one allocation request equips one employee).
  - Most-constrained-first ordering reduces the chance of conflicts late in processing.
- **Observed performance** (from [benchmark report](#performance-tests--reports)):
  P50 = **0.076 ms**, P99 = **0.669 ms** per allocation over 5 000 invocations.

### Tuning the Algorithm

| Knob | Location | Effect |
|------|----------|--------|
| `CANDIDATE_MULTIPLIER` | `AllocationAlgorithm.kt` | Higher value → more candidates considered per slot → higher chance of finding a match. Default 3 is a good balance for typical workloads (S ≤ 4). |
| Scoring weights | `Equipment.score()` in `AllocationAlgorithm.kt` | Adjust `brandBonus` (currently 10.0) to control how strongly brand preference dominates condition score. Setting it to 0 makes brand irrelevant. |
| Slot ordering | `processingOrder` in `AllocationAlgorithm.kt` | Currently most-constrained-first. Could be changed to most-demanded-first for different fairness properties. |

### Trade-offs vs. Other Alternatives

| Approach | Pros | Cons |
|----------|------|------|
| **This greedy approach** ✓ | O(S·K) — linear, simple, sub-millisecond for real workloads | No global optimality guarantee; most-constrained-first ordering reduces but does not eliminate conflicts in overlapping candidate sets. |
| **Backtracking search** | Global optimum within bounded search space | Exponential worst-case — O((G·K)^S) — and higher implementation complexity. |
| **Hungarian algorithm** | Polynomial-time optimal for bipartite matching | Only handles 1-to-1 assignment; doesn't support soft preferences or mixed equipment types elegantly. |
| **ILP solver** | Truly optimal for arbitrary constraints | Heavy dependency, cold-start latency, overkill for S ≤ 4 with simple scoring. |

---

## Why Naïve Parallel Consumers Don't Work

> Full analysis: [`docs/parallelism-research.md`](docs/parallelism-research.md)

A seemingly obvious scaling strategy is to increase RabbitMQ `prefetchCount` and spin up
multiple consumer threads or replica pods, each processing one allocation at a time. Under
high concurrency this approach **collapses** due to database lock contention:

### Root Cause 1 — Deterministic Scoring → Hot Rows

`AllocationAlgorithm.scoreCandidate()` is fully deterministic. Every concurrent
transaction ranks the same items highest, so all transactions race for the same small set
of "elite" equipment rows.

### Root Cause 2 — Locking the Entire Eligible Pool

The original per-request flow called `SELECT … FOR UPDATE SKIP LOCKED` on **all**
candidate IDs (up to 15 000 rows in benchmarks). `SKIP LOCKED` silently drops rows held
by *any* other transaction, so even a single concurrent lock causes the check
`lockedCandidates.size < candidateIds.size` to fail → `AllocationLockContentionException`
→ RabbitMQ retry → exponential retry storm.

### Root Cause 3 — Transaction Amplification

N concurrent HTTP requests → N competing DB transactions → contention scales linearly
with load. Each transaction also materialises all matching rows into JVM heap, adding GC
pressure on top of lock contention.

### Result

Under 5 000 concurrent requests the original per-request approach generated nearly
**100 % contention rate**, flooding the dead-letter queue and causing most allocations to
fail after exhausting retries. Scaling to more consumers/replicas **makes things worse**,
not better, because it increases the number of competing transactions.

---

## Batch Allocation — The Solution

> Source: [`BatchAllocationCollector.kt`](src/main/kotlin/com/tequipy/challenge/domain/service/BatchAllocationCollector.kt),
> [`BatchAllocationService.kt`](src/main/kotlin/com/tequipy/challenge/domain/service/BatchAllocationService.kt)

Instead of one DB transaction per allocation, incoming messages are accumulated and
processed in **batches**. This is the primary fix for contention.

### How It Works

```
HTTP POST /allocations
  → CreateAllocationService (saves PENDING, publishes message)
  → allocation.queue

AllocationMessageListener
  → BatchAllocationCollector.submit(command)
      Puts command into ArrayBlockingQueue (capacity 10 000)
      If queue size ≥ MAX_BATCH_SIZE (100) → eager flush
      Otherwise returns immediately (message ACKed)

BatchAllocationCollector  (@Scheduled every 5 000 ms)
  → flush() — drains up to 100 commands
  → BatchAllocationService.processBatch(commands)  [@Transactional]
        ├─ Idempotency check via AllocationProcessingRepository.tryStart()
        │    Duplicate messages → republish cached result, skip processing
        │
        ├─ ONE findAvailableWithMinConditionScore(allTypes, globalMin)
        │    Single SELECT for the entire batch
        │
        ├─ ONE findByIdsForUpdate(candidateIds, globalMin)
        │    Locks only totalSlots × 2 rows (bounded oversample)
        │
        ├─ Sort commands most-constrained-first
        │
        ├─ For each command:
        │    pool = lockedPool − usedIds
        │    selected = AllocationAlgorithm.allocate(policy, pool)
        │    → ALLOCATED or FAILED
        │
        ├─ Bulk saveAll (one DB round-trip for all reserved equipment)
        │
        └─ Publish AllocationProcessedResult for each command
```

### Why Contention Drops to Near Zero

| Metric | Per-request (original) | Batched (100 / 5 s) |
|--------|----------------------|---------------------|
| Lock scope | All eligible rows (up to 15 000) | `totalSlots × 2` (≤ 8 for a 4-item policy) |
| Transactions under 5 000 requests | 5 000 | ≤ 50 |
| `AllocationLockContentionException` | ~100 % | Effectively 0 |
| DLQ pressure | High | Eliminated |
| Allocation quality | Full | **Identical** (same algorithm, same scoring) |
| Best-case latency | ~immediate | ≤ 5 s window (configurable) |

Since allocation creation already returns **HTTP 202** (accepted, processing is async),
the ≤ 5 s batching window is transparent to API clients.

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `tequipy.batch-allocation.window-ms` | `5000` | Max delay before a partial batch is flushed |
| `tequipy.batch-allocation.queue-capacity` | `10000` | In-memory queue capacity |

---

## Microservice Split Readiness

The current monolith is **designed to split into two independent services** with minimal
effort. The RabbitMQ queues already act as a stable contract boundary between them.

### Natural service boundary

```
┌──────────────────────────────────┐        ┌──────────────────────────────────┐
│  Service A — Allocation API      │        │  Service B — Allocation Processor│
│                                  │        │                                  │
│  • EquipmentController           │        │  • AllocationMessageListener     │
│  • AllocationController          │        │  • BatchAllocationCollector      │
│  • CreateAllocationService       │        │  • BatchAllocationService        │
│  • ConfirmAllocationService      │        │  • ProcessAllocationService      │
│  • CancelAllocationService       │        │  • AllocationAlgorithm           │
│  • AllocationProcessedMsg        │        │  • InventoryAllocationService    │
│    Listener (result consumer)    │        │                                  │
│                                  │        │                                  │
│  DB tables:                      │        │  DB tables:                      │
│   allocations                    │        │   equipments                     │
│   allocation_policy_requirements │        │   allocation_processing_results  │
│   allocation_equipment_ids       │   ──►  │   allocation_processing_         │
│                                  │  queue  │     equipment_ids               │
│  Publishes to:                   │        │                                  │
│   allocation.queue          ─────┼────────┤► Consumes allocation.queue       │
│                                  │        │                                  │
│  Consumes:                  ◄────┼────────┼─ Publishes to:                   │
│   allocation.result.queue        │        │   allocation.result.queue        │
└──────────────────────────────────┘        └──────────────────────────────────┘
```

### Two separate persistence stores

The schema already uses **two independent repository groups** with no cross-joins:

| Repository | Tables | Used by |
|------------|--------|---------|
| `AllocationRepository` | `allocations`, `allocation_policy_requirements`, `allocation_equipment_ids` | Service A — stores the allocation request lifecycle (PENDING → ALLOCATED → CONFIRMED / CANCELLED) |
| `AllocationProcessingRepository` | `allocation_processing_results`, `allocation_processing_equipment_ids` | Service B — stores the processing outcome and idempotency guard |

The only shared reference is `equipment_id` foreign keys, which in a split scenario
become **eventual-consistency references** (the FK constraints would be dropped across
service boundaries).

### What a split would look like

1. **Create two Gradle modules** (or two repositories) from the existing package structure.
2. **Service A** keeps the REST controllers, `AllocationRepository`, and the
   `AllocationProcessedMessageListener` (consumes `allocation.result.queue` to learn
   whether processing succeeded).
3. **Service B** keeps the `AllocationMessageListener`, `BatchAllocationCollector`,
   `BatchAllocationService`, `AllocationProcessingRepository`, and the equipment
   inventory (`EquipmentRepository`).
4. **RabbitMQ queues remain unchanged** — `allocation.queue` carries requests from A → B,
   `allocation.result.queue` carries results from B → A.
5. Each service gets its own database (or schema), eliminating shared-state coupling.

No new messaging contracts are needed — the existing `AllocationRequestedMessage` and
`AllocationProcessedMessage` DTOs already serve as the inter-service API.

---

## API Endpoints

### Equipment

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `POST` | `/equipments` | Register new equipment | `201 Created` |
| `GET` | `/equipments` | List all equipment (optional `?state=` filter) | `200 OK` |
| `POST` | `/equipments/{id}/retire` | Retire available equipment | `200 OK` |

### Allocations

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `POST` | `/allocations` | Create allocation request | `202 Accepted` |
| `GET` | `/allocations/{id}` | Get allocation by ID | `200 OK` |
| `POST` | `/allocations/{id}/confirm` | Confirm (ALLOCATED → CONFIRMED) | `200 OK` |
| `POST` | `/allocations/{id}/cancel` | Cancel allocation | `200 OK` |

### Error Responses

| Condition | HTTP Status |
|-----------|------------|
| Validation / business rule failure | `400 Bad Request` |
| Resource not found | `404 Not Found` |
| Invalid state transition | `409 Conflict` |

### Example Payloads

<details>
<summary>Register equipment</summary>

```json
{
  "type": "MAIN_COMPUTER",
  "brand": "Apple",
  "model": "MacBook Pro",
  "conditionScore": 0.95,
  "purchaseDate": "2025-01-10"
}
```
</details>

<details>
<summary>Retire equipment</summary>

```json
{
  "reason": "Panel damaged"
}
```
</details>

<details>
<summary>Create allocation</summary>

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
</details>

---

## Running with Docker Compose

The [`docker-compose.yml`](docker-compose.yml) brings up PostgreSQL, RabbitMQ, and the
application in one command:

```bash
# Build the image and start all services
docker compose up --build

# Or run in detached mode
docker compose up --build -d

# Verify
curl http://localhost:8080/equipments

# Shut down
docker compose down

# Shut down and remove volumes (full reset)
docker compose down -v
```

The app container waits for healthy PostgreSQL and RabbitMQ before starting.
Flyway automatically applies database migrations on startup.

| Service | Port |
|---------|------|
| Application | `localhost:8080` |
| PostgreSQL | `localhost:5432` |
| RabbitMQ AMQP | `localhost:5672` |
| RabbitMQ Management UI | `localhost:15672` (guest / guest) |

---

## Running on Kubernetes

Kubernetes manifests are in the [`k8s/`](k8s/) directory. The deployment creates **3 replicas**
of the application with health probes, plus PostgreSQL and RabbitMQ deployments.

### Prerequisites

- A Kubernetes cluster (Minikube, kind, Docker Desktop, etc.)
- `kubectl` configured to point at the cluster
- The Docker image built and available to the cluster

### Steps

```bash
# 1. Build the Docker image
docker build -t tequipy-code-challenge:local .

# 2. If using Minikube, load the image into the cluster
minikube image load tequipy-code-challenge:local

# 3. Apply all manifests
kubectl apply -f k8s/

# 4. Wait for pods to be ready
kubectl get pods -w

# 5. Port-forward to access the app
kubectl port-forward svc/tequipy-app 8080:8080

# 6. Verify
curl http://localhost:8080/equipments
```

### Manifest Inventory

| File | Description |
|------|-------------|
| `configmap.yaml` | Non-secret environment variables (DB_HOST, RABBITMQ_HOST, ports) |
| `secret.yaml` | Base64-encoded credentials for DB and RabbitMQ |
| `deployment.yaml` | Application deployment (3 replicas, init containers, health probes) |
| `service.yaml` | ClusterIP service for the application |
| `postgres-deployment.yaml` | PostgreSQL deployment |
| `postgres-pvc.yaml` | Persistent volume claim for PostgreSQL data |
| `postgres-service.yaml` | ClusterIP service for PostgreSQL |
| `rabbitmq-deployment.yaml` | RabbitMQ deployment |
| `rabbitmq-service.yaml` | ClusterIP service for RabbitMQ |

The deployment includes `initContainers` that block startup until PostgreSQL and RabbitMQ
are reachable, plus `startupProbe`, `readinessProbe`, and `livenessProbe` for robust
lifecycle management.

---

## Running Locally (bare-metal)

### Prerequisites

- JDK 17+
- PostgreSQL running on `localhost:5432` (database `tequipy`)
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

### Build and Run

```bash
./gradlew build
./gradlew bootRun
```

---

## Testing

The project includes three test categories:

| Category | What it covers | Requires |
|----------|---------------|----------|
| **Unit tests** | Domain services, allocation algorithm | Nothing (MockK) |
| **Integration tests** | REST controllers, full Spring context, DB + MQ | Docker (Testcontainers) |
| **Performance tests** | End-to-end throughput, latency, contention | Docker (Testcontainers) |

### Commands

```bash
# All unit + integration tests (excludes performance)
./gradlew cleanTest test

# Domain service tests only
./gradlew cleanTest test --tests "com.tequipy.challenge.domain.service.*"

# Controller / integration tests only
./gradlew test --tests "com.tequipy.challenge.adapter.api.web.*"

# Performance tests only (tagged separately)
./gradlew performanceTest
```

> Integration and performance tests use **Testcontainers** and require a running Docker daemon.

---

## Performance Tests & Reports

Two performance test suites validate the system under high concurrency:

### 1. MixedEquipmentPerformanceTest

> Source: [`src/test/kotlin/.../performance/MixedEquipmentPerformanceTest.kt`](src/test/kotlin/com/tequipy/challenge/performance/MixedEquipmentPerformanceTest.kt)

Simulates **5 000 concurrent allocation requests** with mixed equipment types (1–4 random
requirements per request across MAIN_COMPUTER, MONITOR, KEYBOARD, MOUSE). Seeds ~25 000
equipment items at 2× actual demand.

**Latest results** (from [`build/mixed-equipment-performance-report.md`](build/mixed-equipment-performance-report.md)):

| Phase | Metric | Value |
|-------|--------|------:|
| HTTP Submission | Throughput | 160.9 req/s |
| HTTP Submission | P50 response | 54 ms |
| HTTP Submission | P99 response | 160 ms |
| Async Processing | ALLOCATED | 5 000 / 5 000 |
| Async Processing | FAILED | 0 |
| Async Processing | Throughput | 87.7 alloc/s |
| Algorithm Speed | P50 | 0.076 ms |
| Algorithm Speed | P99 | 0.669 ms |
| Algorithm Speed | Throughput | 8 394 alloc/s |
| **End-to-end** | **Total wall-clock** | **88 s** |

### 2. HighContentionPerformanceTest

> Source: [`src/test/kotlin/.../performance/HighContentionPerformanceTest.kt`](src/test/kotlin/com/tequipy/challenge/performance/HighContentionPerformanceTest.kt)

A worst-case contention scenario: **5 000 concurrent requests**, each asking for 1 Apple +
1 Dell `MAIN_COMPUTER` with `minimumConditionScore ≥ 0.7`. All requests compete for the
same pool of equipment — the scenario that originally caused 100 % lock contention before
the batch solution.

### Running Performance Tests

```bash
./gradlew performanceTest
```

Reports are written to:
- `build/mixed-equipment-performance-report.md`
- `build/performance-report.md`

The Gradle HTML test report is also available at
`build/reports/tests/performanceTest/index.html`.

---

## CI / CD

### CI Pipeline (`.github/workflows/ci.yml`)

Runs automatically on every **push** and **pull request** to `main` / `master`:

```
Checkout → JDK 17 setup → Gradle cache restore → Build → Test → Publish test results
```

- Uses `ubuntu-latest` runner with Docker support for Testcontainers
- Gradle packages are cached between runs for faster builds
- Test results are published as GitHub check annotations via `dorny/test-reporter`
- Performance tests are **excluded** from CI (`@Tag("performance")` is filtered out by
  the default `test` task)

### Performance Pipeline (`.github/workflows/performance.yml`)

Triggered **manually** via `workflow_dispatch` (Actions → Performance Test → Run workflow):

1. **Job 1 — `run-tests`:** checks out the PR's code (read-only permissions — untrusted
   PR code cannot access the GitHub token), runs `./gradlew performanceTest`, and uploads
   the report as an artifact.
2. **Job 2 — `post-comment`:** downloads the artifact and posts/updates a comment on the
   PR with the full performance report. This job has write permissions but never executes
   PR code, ensuring security.

This two-job split follows the **principle of least privilege**: untrusted code runs in a
sandboxed job, and the privileged comment-posting job only touches the artifact.

---

## Notes for Contributors

- Treat **tests as the primary behavioral contract** — when docs and code differ, trust
  tests first.
- The allocation processing flow is **idempotent by design** — duplicate RabbitMQ
  deliveries are expected and handled safely via `AllocationProcessingRepository`.
- Do **not** add `/api` prefixes to endpoints unless explicitly requested.
- Keep DTO validation annotations aligned with domain validation rules.
- If you change the allocation algorithm or scoring, update both algorithm unit tests and
  integration tests together.

# Incident Intelligence Engine

An incident investigation system that produces an **evidence-ranked root-cause report**
from real telemetry, without ever being told the answer.

The project has two Spring Boot services and a shared contracts module:

- **`incident-simulator`** runs four logical microservices, generates traffic, emits
  metrics/logs/traces/deployments, and raises incidents from thresholds it observes.
- **`investigation-engine`** investigates those incidents through a bounded,
  allowlisted tool surface, proposes competing hypotheses, scores them from the
  evidence they cite, and persists an explainable report.

The point of the project is the part most AI demos skip: the diagnosis has to be
derived from the telemetry, the confidence has to be computable from what was
observed, and the agent has to remain safe when the telemetry is hostile.

```
  operator                     incident-simulator                     investigation-engine
 ─────────                    ──────────────────                     ────────────────────
 POST /api/scenarios   ─────▶  inject failure mode
 POST /api/traffic     ─────▶  simulate order flow  ──▶ spans, logs, metrics
 POST /api/detection   ─────▶  threshold detection   ──▶ incident (symptoms only)
                                                                    │
 POST /api/investigations { incidentId }  ─────────────────────────▶ │
                                                                    ▼
                                                         bounded planner loop
                                                       (allowlisted read-only tools)
                                                                    │
                                                                    ▼
                                                          evidence ledger
                                                                    │
                                                                    ▼
                                                     hypothesis scoring + ranking
                                                                    │
                                                                    ▼
                                                    report + advisory recommendations
```

## Quick start (no Docker required)

Requirements: JDK 21 only. The Maven Wrapper downloads Maven 3.9.9 on first use,
so no global Maven install is needed. Both applications default to PostgreSQL; the
local `dev` profile switches them to in-memory H2 so nothing else is needed.

```bash
# Terminal 1 — simulator on :8081
./mvnw -q -DskipTests package
java -jar incident-simulator/target/incident-simulator-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  --simulator.traffic.enabled=false \
  --simulator.detection.enabled=false

# Terminal 2 — engine on :8082
java -jar investigation-engine/target/investigation-engine-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=dev
```

Schedulers are disabled above so every step is explicit. Drop those two flags to let
traffic and detection run on their own intervals instead.

### Drive one incident end to end

```bash
# 1. Inject a failure mode into the simulated fleet.
curl -s -X POST http://localhost:8081/api/scenarios \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"connection-pool-exhaustion","durationSeconds":300}'

# 2. Generate traffic. Real traces, logs, and metrics are recorded as a side effect.
curl -s -X POST 'http://localhost:8081/api/traffic?count=90'

# 3. Run detection. The incident text contains only observable symptoms.
INCIDENT=$(curl -s -X POST http://localhost:8081/api/detection/run | jq -r .id)

# 4. Investigate.
curl -s -X POST http://localhost:8082/api/investigations \
  -H 'Content-Type: application/json' \
  -d "{\"incidentId\":\"$INCIDENT\"}" | jq '{
    status: .investigation.status,
    toolCalls: (.steps | length),
    evidence: (.evidence | length),
    hypotheses: [.hypotheses[] | {rank, category, confidence}],
    summary: .report.summary
  }'
```

Observed result for that scenario:

```json
{
  "status": "COMPLETED",
  "toolCalls": 10,
  "evidence": 16,
  "hypotheses": [
    { "rank": 1, "category": "CONNECTION_POOL_EXHAUSTION", "confidence": 0.54 },
    { "rank": 2, "category": "DEPLOYMENT_REGRESSION", "confidence": 0.0 }
  ],
  "summary": "Most likely cause for the incident on payment-service: Database connection pool exhaustion on payment-service (evidence score 0.54, rank 1 of 2). Derived from 10 tool calls and 16 evidence items."
}
```

The score is 0.54 when the investigation starts immediately after the traffic burst,
because the sampled metric series has not yet caught up with the live health snapshot:

```
distinct supporting categories: logs, traces, health (0.58);
contradicting categories: 0 (-0.00); missing checks: 1 (-0.04); score 0.540
```

Run the same investigation again after one scrape interval (two seconds) and the
pool series shows `latest=0.980`, adding the metrics category:

```json
{ "rank": 1, "category": "CONNECTION_POOL_EXHAUSTION", "confidence": 0.9 }
```

```
distinct supporting categories: metrics, logs, traces, health (0.90);
contradicting categories: 0 (-0.00); missing checks: 0 (-0.00); score 0.900
```

That difference is intentional. The score describes the evidence that was collected,
not a fixed truth, and a lagging data source costs the hypothesis its category weight
instead of being silently cited as support.

## Supported scenarios

| Scenario slug | Injected failure | Detected on | Correct top hypothesis |
| --- | --- | --- | --- |
| `connection-pool-exhaustion` | Database connection pool saturates | `payment-service` | `CONNECTION_POOL_EXHAUSTION` |
| `memory-leak` | Heap retention with GC pressure | `order-service` | `MEMORY_PRESSURE` |
| `slow-inventory-dependency` | Downstream latency, caller healthy | `inventory-service` | `DOWNSTREAM_DEPENDENCY_LATENCY` |

The scenario slug is accepted only by the operator control endpoint. It is not written
into incidents, telemetry, logs, span attributes, or any engine-visible field.

## How the investigation works

1. **Trigger.** Detection compares observed health against thresholds — error rate,
   p99 latency, heap utilisation, pool utilisation — and opens an incident whose
   description restates those symptoms only.
2. **Plan.** The planner receives the incident (id, symptoms, primary service) and a
   tool catalog. It requests one allowlisted read-only tool per step.
3. **Execute.** The orchestrator runs the tool with a timeout and a bounded retry,
   records structured data for the planner, and appends sanitised evidence to a
   ledger.
4. **Adapt.** After traces are collected, the planner adds a health query for the
   slowest *declared dependency* found in those traces. It never guesses.
5. **Conclude.** The analyzer proposes competing hypotheses and links each to
   specific evidence ids. A recent deployment on the incident's service is always a
   competing hypothesis, so timing correlation cannot masquerade as causation.
6. **Score.** `HypothesisEvaluator` computes confidence from distinct evidence
   categories. Hypotheses are ranked; only hypotheses above a floor produce
   recommendations.
7. **Report.** The report, evidence ledger, steps, and ranked hypotheses are stored as
   one immutable document and exposed over the API.

Full details: [`docs/architecture.md`](docs/architecture.md).

## Confidence is computed, not asserted

A hypothesis is scored by the number of **distinct evidence categories** that support
it, not by the number of samples or an LLM's self-report:

| Category | Weight |
| --- | --- |
| metrics | 0.32 |
| logs | 0.24 |
| traces | 0.24 |
| health | 0.10 |
| deployments | 0.10 |
| topology | 0.05 |

One category counts once, however many samples it contains. Contradicting categories
subtract 0.12 each (capped at 0.36); unanswered checks subtract 0.04 each (capped at
0.16). The score is clamped to `[0, 1]`.

This is an evidence score, not a calibrated probability, and the API says so: every
assessment includes the full calculation.

## Safety model

Telemetry is treated as untrusted input.

- **Fixed tool allowlist.** Six read-only tools: `get_service_topology`,
  `get_service_health`, `query_metrics`, `search_logs`, `find_slow_traces`,
  `get_recent_deployments`. The planner can request a tool by name; anything else
  fails closed.
- **No execution surface.** No shell, no HTTP passthrough, no writes, no remediation.
  Recommendations are advisory text.
- **Sanitisation.** Control characters are stripped, whitespace collapsed, common
  credential patterns redacted, and content truncated before storage or display.
- **Bounded loop.** Max steps, tool calls, wall-clock time, per-call timeout, retries,
  and consecutive-failure budget are enforced by the orchestrator, not the planner.
- **Failure is visible.** An investigation that exhausts a budget is persisted as
  `FAILED` with a reason; it is never silently reported as a weak conclusion.

A guardrail test drives the orchestrator with a hostile planner that requests
`run_shell`; the run fails closed after three rejected calls and persists the failure.

## API surface

### Simulator (`:8081`)

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/scenarios` | Activate a failure mode |
| `DELETE` | `/api/scenarios` | Clear injected failures |
| `GET` | `/api/scenarios` | List active scenarios |
| `POST` | `/api/traffic?count=N` | Generate order traffic |
| `POST` | `/api/detection/run` | Run one detection pass |
| `GET` | `/api/incidents`, `/api/incidents/{id}` | Read incidents |
| `GET` | `/api/services`, `/api/services/{service}/health` | Topology and health |
| `GET` | `/api/telemetry/metrics` | Metric series |
| `GET` | `/api/telemetry/logs` | Log entries |
| `GET` | `/api/telemetry/traces`, `/api/telemetry/traces/{id}` | Traces |
| `GET` | `/api/telemetry/deployments` | Recent deployments |

### Engine (`:8082`)

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/investigations` | Run an investigation synchronously |
| `GET` | `/api/investigations/{id}` | Full investigation document |
| `GET` | `/api/investigations?incidentId=...&limit=...` | Recent summaries |
| `GET` | `/api/investigations/{id}/report` | Report only |
| `GET` | `/actuator/health` | Liveness |

Error handling is uniform: `404` for an unknown incident or investigation, `400` for
malformed input, `500` without internal detail (details are logged, not returned).

## Build and test

All commands use the Maven Wrapper, which pins Maven 3.9.9:

```bash
./mvnw test        # 37 tests: 16 simulator, 21 engine
./mvnw package     # produces two executable jars
./mvnw verify      # what CI runs: build, tests, and packaging
```

`mvnw` works on macOS/Linux and `mvnw.cmd` on Windows. The wrapper downloads its
Maven distribution and dependencies on first use, then caches them under `~/.m2`.

### Continuous integration

`.github/workflows/ci.yml` runs on every pull request and on pushes to `main`:

- checks out the repository and sets up Temurin JDK 21,
- caches the Maven dependency cache via `actions/setup-java`,
- executes `./mvnw -B verify` for the complete multi-module build,
- fails the job if compilation or any test fails.

The workflow is committed but has not run on GitHub from this environment; the
same command is what was executed locally.

Tests never contact a live LLM or a live simulator. The engine tests use an in-memory
`TelemetryGateway` and assert on the real tool catalog, planner, evaluator,
orchestrator, and persistence path. The suite covers:

- all three incident shapes plus a lagging-metrics variant,
- scoring arithmetic, penalties, and caps,
- sanitisation and credential redaction,
- tool allowlisting and hostile-planner containment,
- HTTP create/get/report/list flows and error responses,
- snapshot-and-analysis realism in the simulator.

## Docker Compose

`docker-compose.yml` runs PostgreSQL, both applications, and health-gated startup:

```bash
cp .env.example .env      # change POSTGRES_PASSWORD
docker compose up --build
```

Both applications share one PostgreSQL instance but own separate schemas
(`simulator`, `engine`). Flyway creates and migrates each schema from the same
migration files used against H2.

> **Verification note:** Docker is not installed on the machine where this was
> developed, so `docker-compose.yml` and the Dockerfiles have been written against
> the documented behaviour but could not be executed locally. Everything else in
> this README was run and verified.

## Configuration

Every limit is externalised and is the same value the orchestrator enforces:

| Variable | Default | Meaning |
| --- | --- | --- |
| `INVESTIGATION_MAX_STEPS` | 14 | Planner turns |
| `INVESTIGATION_MAX_TOOL_CALLS` | 12 | Tool executions |
| `INVESTIGATION_MAX_DURATION_SECONDS` | 120 | Wall-clock budget |
| `INVESTIGATION_TOOL_TIMEOUT_MS` | 5000 | Per-call timeout |
| `INVESTIGATION_TOOL_RETRIES` | 1 | Retries per call |
| `INVESTIGATION_MAX_CONSECUTIVE_FAILURES` | 3 | Abort threshold |
| `SIMULATOR_BASE_URL` | `http://localhost:8081` | Simulator address |
| `DB_SCHEMA` | `public` | Flyway schema |

## Project layout

```
contracts/                 shared DTOs and metric names
incident-simulator/        four-service simulation, telemetry stores, detection, persistence
investigation-engine/      planner, tools, evidence ledger, evaluator, orchestrator, report API
docs/architecture.md       design, flows, scoring method, limits, trade-offs
docker-compose.yml         PostgreSQL + both applications
Dockerfile.simulator       multi-stage build for the simulator
Dockerfile.engine          multi-stage build for the engine
mvnw, mvnw.cmd, .mvn/      Maven Wrapper (pinned Maven 3.9.9)
.github/workflows/ci.yml   pull-request and main-branch build
```

## What this is not (yet)

- **No live LLM planner.** `InvestigationPlanner` is a port with a deterministic
  scripted implementation. A Spring AI planner can be dropped in behind the same
  interface; the tool layer, evaluator, and report are unchanged, and tests keep
  running without a model.
- **No Kafka.** Investigations are synchronous and bounded. Kafka becomes justified
  when incidents are dispatched asynchronously and progress is streamed; adding it
  earlier would be infrastructure without a consumer.
- **No Prometheus/Loki/Tempo/Grafana.** The simulator exposes bounded in-memory
  telemetry stores plus Micrometer gauges. `TelemetryGateway` is the seam where real
  backends plug in.
- **No dashboard.** The API returns complete, readable JSON; a UI is a next step.
- **One host, one instance.** No authentication, multi-tenancy, or horizontal
  scaling. Those are deployment concerns, not investigation-logic concerns.

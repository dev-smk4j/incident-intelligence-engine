# Architecture

This document explains how the system is built, why the boundaries sit where they
do, and which trade-offs were chosen deliberately. The README covers usage; this
covers design.

## 1. Two services, one shared contract

```
contracts/                  plain DTOs, no framework dependencies
  IncidentView              symptom-level incident record
  ServiceHealth             point-in-time health snapshot
  MetricSeries / MetricSample
  LogEntryView, SpanView, TraceView
  ServiceNode / ServiceTopology
  DeploymentView
  MetricNames               canonical metric identifiers

incident-simulator/         Spring Boot app, port 8081
  runtime/                  four logical services + order flow simulation
  telemetry/                bounded in-memory metric/log/trace/deployment stores
  detection/                threshold detection -> incidents
  incident/                 incident persistence (JdbcClient + Flyway)
  deployment/               seeded deployment history
  api/                      operator + telemetry read endpoints

investigation-engine/       Spring Boot app, port 8082
  planner/                  InvestigationPlanner port, scripted planner, analyzer
  tools/                    TelemetryGateway port, tool catalog, registry
  investigation/            orchestrator, ledger, sanitizer, evaluator, recommendations
  persistence/              investigation document store
  api/                      investigation endpoints
```

`contracts` exists so neither service depends on the other's internals and so the
engine's tool adapter can be swapped for a real telemetry backend without touching
DTOs. The contract module has no Spring or Jackson annotations beyond what the
records themselves provide, which keeps it usable from tests and future clients.

## 2. Why a simulator at all

The project's claim is that diagnosis is derived from telemetry. That claim is only
testable if something emits realistic, causally consistent telemetry. The simulator
is therefore a real small system, not a fixture generator:

- one order flows sequentially through `order-service`, `inventory-service`,
  `payment-service`, and `shipping-service`;
- each hop records a span, a log line, and contributes to the caller's latency;
- failures propagate: a saturated `payment-service` makes `order-service` return
  errors, which is what a genuine cascade looks like;
- metrics are sampled on an interval from the same runtime state, so metrics lag
  health exactly as a real scrape interval does;
- every service has a recent deployment so deployment regression is always a
  plausible competing explanation.

Three failure modes are injectable for a bounded duration:

| Mode | Mechanism | Observable consequence |
| --- | --- | --- |
| connection pool exhaustion | pool permits exhausted, acquisition times out | high error rate, p99 ≈ acquisition timeout, saturated pool, `Connection acquisition timed out` logs |
| memory leak | heap retention grows, GC pauses lengthen | saturated heap, rising CPU and latency, `GC pause` warnings |
| slow dependency | `inventory-service` response time inflates | caller latency high, caller resources normal, one very slow downstream span |

### Detection is threshold-based, not scenario-based

`AnomalyDetector` reads only `ServiceHealth` and applies configured thresholds
(error rate, p99 latency, heap, pool). It never reads the active scenario. When
several services are anomalous it ranks them by

```
severity = p99 + errorRate*1000 + heap*500 + pool*1000
```

Resource saturation is weighted heavily on purpose. In a cascade the caller usually
has the highest absolute latency, so ranking by latency alone would blame the victim
on every dependency incident.

The incident record contains a title and a description made of measured numbers. It
does **not** contain the scenario slug, the target service, the failure mode, or any
attribute that identifies the injection.

## 3. The investigation loop

### Ports

```java
interface InvestigationPlanner {
    PlanDecision next(InvestigationContext context);
}

sealed interface PlanDecision permits PlanDecision.CallTool, PlanDecision.Conclude {}
```

A planner may do exactly two things: request one tool call by name with arguments, or
stop and propose hypotheses. It cannot execute code, call a URL, or loop on its own.

`ScriptedInvestigationPlanner` implements the baseline strategy:

1. `get_service_topology`
2. `get_service_health(primary)`
3. five `query_metrics(primary, metric)` calls for error rate, p99 latency, pool
   utilisation, heap utilisation, CPU utilisation
4. `search_logs(primary)`
5. `find_slow_traces(primary, minDurationMs=500)`
6. `get_recent_deployments(primary)`
7. *adaptive*: `get_service_health(slowest dependency seen in those traces)`
8. conclude

The adaptive step is the only place the plan depends on results. The dependency must
appear both in the topology and in a slow span, so the planner cannot invent a target
or follow text found inside telemetry.

Ten steps for a local failure, eleven for a dependency failure. Both sit inside the
default 14-step / 12-tool-call budget.

### Evidence

`TelemetryToolCatalog` turns each tool result into structured data plus one or more
`EvidenceDraft`s. `EvidenceLedger` sanitises and stores them with stable ids
(`ev-001`, `ev-002`, …) scoped to the investigation.

Ids matter: hypotheses cite them, and the report can be read without re-querying any
telemetry backend. If a backend is unavailable after the fact, the report still
stands.

Every evidence item records its category in the source prefix (`metrics:`,
`logs:`, `traces:`, `health:`, `deployments:`, `topology`). That prefix is what the
evaluator uses for scoring; it is assigned by the tool layer, not by the planner.

### Sanitisation

Telemetry can contain control characters, forged line breaks, credentials an
application logged by accident, and text that looks like instructions.
`TelemetrySanitizer` strips control characters, collapses whitespace, redacts
credential-shaped assignments and authorization headers, and truncates content to
2,000 characters with an explicit marker.

This is data hygiene, not prompt-injection defence, and the code says so. Real
protection comes from three properties: the planner only chooses allowlisted tool
names, tool arguments are validated rather than concatenated, and nothing in the
system can change production state.

### Failure semantics

`ToolExecutor` converts every outcome — success, timeout, exception, invalid
arguments — into a `ToolExecution`. Nothing escapes as an exception.

- invalid arguments fail immediately (retrying cannot help);
- timeouts and transient failures are retried once by default;
- each call runs on a virtual thread with a hard timeout;
- the orchestrator aborts after `maxConsecutiveFailures` failures.

The orchestrator also enforces step count, tool-call count, and wall-clock budget
independently of the planner. When a budget is exhausted the investigation is
persisted as `FAILED` with a human-readable reason. It never degrades into a
confident-sounding guess.

## 4. Hypothesis generation

`RuleBasedHypothesisAnalyzer` runs when the planner concludes. It reads only the
structured tool results.

**Connection pool exhaustion** — proposed when pool utilisation is at saturation
(≥ 0.90) in the fresh health snapshot *or* in sampled metrics. Supported by: the
saturated health evidence, the saturated metric point (only when it is actually
saturated), connection-acquisition log lines, latency metrics (only when p99
≥ 1,000 ms), and slow traces. A latency series that exists but stays below the
threshold is recorded as an unanswered check — *"Request latency metric samples did
not show elevated latency"* — rather than cited as support.

**Memory pressure** — proposed when heap is saturated (≥ 0.90) in health or metrics.
Supported by the saturated heap evidence, GC-pause logs, CPU metrics (only when
CPU ≥ 0.70), latency metrics (only when p99 ≥ 1,000 ms), and traces. Missing GC logs,
unobserved CPU, and non-elevated latency are each recorded as an unanswered check
rather than ignored.

**Downstream dependency latency** — proposed when a span belonging to a
*topology-declared dependency* of the primary service exceeds 1,000 ms. Supported by
the slow span, caller latency metrics (only when p99 ≥ 1,000 ms), caller health, the
dependency's health (fetched by the adaptive step), the topology edge, and the
caller's **normal** pool (< 0.50) and heap (< 0.70) metric points. Those normal
resources are supporting evidence here, not contradictions: the caller is slow
despite having spare capacity, which is what rules out a local cause. The hypothesis
carries no contradicting evidence. A lagging latency series is recorded as
*"Request latency metric samples had not caught up with the incident"*.

**Deployment regression** — proposed whenever the primary service has a deployment
inside the lookback window. It is intentionally weak evidence on its own: one
deployment category is worth 0.10, and timing alone is not causation.

Why the health snapshot is treated as first-class evidence: metric samplers lag live
state by up to one interval. A real monitor can show p99 = 0.0 while health already
reports p99 = 3,432 ms. If the analyzer required a saturated metric point, the
correct hypothesis would be missing for the first scrape after the incident starts.
The analyzer therefore diagnoses from the freshest source and cites only the metric
points that genuinely show the condition.

This splits two questions that are easy to conflate: *is the fault present?* and *can
this data point honestly be cited?* Diagnosis uses `max(health, metrics)` so a
saturation is still recognised immediately. Citation requires the individual point
to show the condition, because a 0.12 pool sample does not evidence exhaustion even
when the live pool is at 0.98. The consequence is visible in the score: an
investigation started in the first scrape interval scores lower and lists the
lagging series as missing, while the same investigation rerun one interval later
picks up the metrics category. Tests pin both the immediate and the caught-up case.

## 5. Confidence: a documented evidence score

`HypothesisEvaluator` computes:

```
support       = min(1, sum of weights of distinct supporting categories)
contradiction = min(0.36, 0.12 * distinct contradicting categories)
missing       = min(0.16, 0.04 * unanswered checks)
score         = clamp(support - contradiction - missing, 0, 1), rounded to 3 dp
```

Weights: metrics 0.32, logs 0.24, traces 0.24, health 0.10, deployments 0.10,
topology 0.05.

Design decisions worth stating:

- **Distinct categories, not sample counts.** Ten metric points are not ten
  independent confirmations; they are one signal. Counting them would let a
  high-frequency data source dominate the ranking.
- **Category weights reflect diagnostic strength.** Metrics and traces are direct
  measurements of the failure mechanism; topology is context, not evidence of a
  fault.
- **The score is not a probability.** It is a reproducible ranking function. The
  evaluator exposes `confidenceBasis` with the arithmetic, and the API labels the
  field an evidence score. Calling it a probability would be dishonest without
  calibration data.
- **Contradictions have a cap.** Otherwise a hypothesis with one supporting category
  and many contradicting ones could go arbitrarily negative, which carries no extra
  information but makes sorting less stable.

The evaluator also rejects evidence ids that do not exist, counting them as
unresolved links and noting them in the basis string. That surfaces a bug in a
planner instead of silently crediting support.

### Ranking and recommendations

Hypotheses are sorted by score, then by statement for deterministic ties, and
renumbered. `RecommendationGenerator` emits advisory text per hypothesis above a
score floor of 0.20. Each recommendation carries an action, a rationale tied to
evidence, a risk of acting, and a success criterion. Below the floor, the report
recommends collecting more data instead of acting on speculation.

No recommendation is executable.

## 6. Persistence

Simulator: `incident` table (Flyway `V1__initial_schema.sql`), deduplicated per
service with a 10-minute window so a sustained fault does not create an incident per
detection tick.

Engine: one `investigation` table holding indexed metadata plus the complete result
document as JSON text:

```sql
id, incident_id, status, error_message, document, created_at, completed_at
```

Rationale: an investigation is an append-only audit record. Reports are written once
and read back whole. Normalising evidence into its own table would add joins and
migrations without enabling a query the product needs. `TEXT` rather than `JSONB`
keeps the same migration valid on H2 (local dev/tests) and PostgreSQL (containers).

The trade-off is explicit: ad-hoc querying inside a report requires reading the
document. If cross-incident evidence analytics become a requirement, projecting the
document into typed tables is the next step — but that is a reporting concern, not a
reason to complicate the write path today.

## 7. API and error model

`InvestigationController` runs synchronously and returns `201` with the full result.
Synchronous is a deliberate simplification: the loop is bounded by construction, so
the request cannot hang indefinitely, and the whole behaviour is observable in one
response. Streaming progress requires a queue, which is where Kafka earns its place.

`ApiExceptionHandler` maps:

| Situation | Status | Body |
| --- | --- | --- |
| unknown incident | 404 | `{"error": "Incident not found: ..."}` |
| unknown investigation | 404 | `{"error": "Investigation not found: ..."}` |
| bad arguments | 400 | validation message |
| malformed JSON | 400 | `{"error": "Malformed request body"}` |
| anything else | 500 | `{"error": "Internal server error"}` |

Internal detail — SQL fragments, simulator URLs, stack traces — is logged, never
returned.

## 8. Testing strategy

The engine tests run against an in-memory `TelemetryGateway`, not a live simulator:
the loop is deterministic and fast, and the same tool catalog, planner, evaluator,
orchestrator, and persistence code paths execute. The simulator tests exercise the
runtime and detection directly, including trace causality and incident text hygiene.

`ScenarioFixtures` constructs symptom-level telemetry for each failure class. The
fixtures deliberately never place the scenario slug in any field the engine can read,
and a shared assertion verifies that evidence contains neither `scenario` nor
`failure mode`.

Guardrails are tested adversarially: a test planner that requests `run_shell` with
`rm -rf /` must produce a failed investigation with zero evidence and no report.

## 9. Deployment

`docker-compose.yml` runs PostgreSQL 16, the simulator, and the engine. Both app
images are multi-stage Maven builds that run as a non-root user. Startup is gated on
PostgreSQL health; the engine also waits for the simulator to be healthy. Each app
owns a PostgreSQL schema (`simulator`, `engine`) with its own Flyway history.

Known gap: Docker is not installed on the development machine, so the compose file
and Dockerfiles are reviewed but not executed. The H2 profiles and the full test
suite are the verified path.

## 10. Extension points and deliberate omissions

**Spring AI planner.** `InvestigationPlanner` is the seam. A model-backed planner
receives the same `InvestigationContext` (incident symptoms, prior executions,
evidence ids) and must return `CallTool` or `Conclude`. Adding it requires no change
to tools, scoring, persistence, or the API, and the deterministic planner stays as
the test baseline and fallback. Tool names are already allowlisted, so a model can
only choose among read-only queries.

**Real telemetry backends.** `TelemetryGateway` is implemented once against the
simulator's HTTP API. Prometheus/Loki/Tempo adapters implement the same seven
methods. Because tools depend on the port, no investigation logic changes.

**Kafka.** Justified when incidents are dispatched asynchronously, investigations
outlive an HTTP request, or progress is streamed to a UI. Until then it would be
infrastructure without a consumer.

**Remediation.** Out of scope by design. Recommendations are text for a human to
evaluate. Autonomous remediation fed by untrusted telemetry is a different risk class
and would need approvals, dry runs, and rollback guarantees.

**OpenTelemetry.** The simulator emits real spans internally and Micrometer gauges
externally. Wiring OTLP export and a Grafana stack is valuable for operational
realism but would not change the investigation logic; it is deferred rather than
faked.

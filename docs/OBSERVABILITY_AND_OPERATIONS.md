# Document Store observability and operations

## Purpose and delivery boundary

This runbook defines privacy-safe signals and deterministic response procedures
for Document Store. The repository publishes vendor-neutral Micrometer metrics,
redacted structured logs, correlation propagation, and liveness/readiness
health groups. It does not create a monitoring account, dashboard, AWS
resource, pager integration, or other paid service.

Infrastructure may later bind these signals to the approved AWS monitoring
stack. Enabling CloudWatch custom metrics, an OpenTelemetry collector, log
retention, synthetic schedules, notifications, or paging requires an explicit
cost and retention decision. Repository verification uses only local synthetic
data.

## Signal catalogue

Metric names use Micrometer dotted notation. Exporters may translate dots to
underscores and add counter suffixes.

| Metric | Type | Bounded labels | Meaning |
| --- | --- | --- | --- |
| `document.store.operation.count` | Counter | `resource`, `operation`, `outcome`, `reason`, `document_type`, `file_type` | Successful and failed create, storage, retrieval, list, delete, activation, deactivation, export-storage and export-retrieval operations |
| `document.store.operation.duration` | Timer | Same as operation count | End-to-end repository service-operation latency |
| `document.store.payload.size` | Distribution summary, bytes | `resource`, `direction`, `document_type`, `file_type` | UTF-8 generated-text bytes and binary export bytes stored or retrieved |
| `document.store.reconciliation.count` | Counter | `resource`, `outcome`, `reason` | Bounded consistency anomalies detected or repaired; the current service records repairs of multiple active versions |
| `document.store.access.denied.count` | Counter | `route`, `reason` | Authentication and authorization denials by redacted route family |

Allowed resource labels are `document` and `file`. Operation labels are a
closed set: `create`, `retrieve`, `list`, `delete`, `activate`, `deactivate`,
`store_export`, `replace_export`, `retrieve_export`, and `list_export`.
Outcomes and reasons are likewise closed. Unknown or unapproved values collapse
to `other`; they never become a new time series.

Document/file types come only from the application enums. Routes are reduced to
families such as `/api/v1/documents/**`; UUIDs and owner path segments are never
labels. Payload bytes are observations, not labels.

The standard Spring health signals are:

| Endpoint | Intended meaning |
| --- | --- |
| `/actuator/health/liveness` | The process can continue running. It must not depend on the database or a remote provider. |
| `/actuator/health/readiness` | Application readiness state, database health, and `documentStorage` are all healthy. |

`documentStorage` verifies the required database connection and performs a
read-only availability check through the provider-neutral object-storage
interface. The production adapter verifies private-bucket reachability; the
isolated adapter verifies that its root remains readable and writable.
Metadata and binary storage are reported separately with
fixed `reachable`/`unavailable` details. Health details never include a JDBC
URL, host, role, credential, exception, bucket, storage key, or document data.
The production adapter uses the configured private S3-compatible store.

## Logging and privacy rules

Application logs may include:

- timestamp, level, service name, safe correlation ID;
- HTTP method, redacted route family and status;
- bounded resource/operation/outcome/reason/type values;
- duration, payload size and affected-record count; and
- fixed dependency state such as `reachable` or `unavailable`.

Application logs and metric labels must never include:

- document content, prompts, CV/cover-letter text or exported bytes;
- titles, personal filenames, free-text validation values or support notes;
- raw user, job, application, document, file or storage identifiers;
- access/service tokens, cookies, authorization headers or credentials;
- JDBC/object-store URLs, bucket names, object keys or exception messages; or
- a checksum/hash that becomes a durable cross-system personal-data key.

An accepted `X-Correlation-Id` is at most 64 characters and contains only
letters, numbers, `.`, `_`, or `-`. Unsafe input is replaced. The filter returns
the safe ID, stores it in MDC for the request, propagates it through configured
Spring HTTP clients, and clears it after completion.

Operators must not temporarily enable SQL, bind-value, request-body, response-
body or document-content logging during an incident.

The repository disables the H2 console, SQL statement logging, bind-value
logging, connection-pool INFO output and Flyway INFO output by default.
Development overrides must preserve those controls whenever any non-synthetic
data or credential is present. Spring Web debug logging and its first-request
route-cache warning plus Spring JDBC debug statements are also suppressed
because those framework messages can contain an unredacted request path or
database statement.

## Dashboard and alert contract

These thresholds are initial private-beta guardrails. Tune them only from
retained synthetic/production aggregate evidence; never lower privacy controls
to debug an alert.

| Signal | Initial condition | Action |
| --- | --- | --- |
| Readiness | Two consecutive failed probes or continuously down for 60 seconds | Page the service owner; stop new document writes at the gateway if safe |
| Liveness | One failed probe after the platform restart allowance | Page platform owner and retain restart/crash evidence |
| Operation failure ratio | More than 5% over 5 minutes with at least 10 operations | Warn and investigate by operation/reason |
| Operation failure ratio | More than 20% over 5 minutes with at least 10 operations | Page; consider disabling the affected write path |
| Create/store p95 | Over 2 seconds for 10 minutes | Warn; inspect dependency health and saturation |
| Retrieve/export p95 | Over 1 second for 10 minutes | Warn; inspect storage latency and response sizes |
| Reconciliation | Any `failure`, or repeated `repaired` events in 15 minutes | Page for failure; warn for repeated repair |
| Access denial | More than 20/minute and three times the established hourly baseline | Warn for credential/configuration misuse; do not include identity values |

Do not alert on ratios without the minimum event count. A single expected 404,
401 or 403 is not an availability incident.

The minimum dashboard groups are:

1. liveness/readiness and deployment markers;
2. operation count and failure ratio by bounded operation/reason;
3. p50/p95/p99 duration by operation;
4. stored/retrieved payload distributions by document/file type;
5. reconciliation repairs/failures; and
6. authentication/authorization denials by safe route family.

## Cost-free synthetic path

Before a beta release, exercise this path locally or in the approved isolated
test environment with synthetic content only:

1. require `liveness=UP` and `readiness=UP`;
2. create one synthetic CV document using an approved synthetic owner;
3. retrieve it and verify only the synthetic content;
4. store a minimal synthetic PDF export and retrieve its bytes;
5. repeat one denied request and verify the stable 401/403 response;
6. remove the synthetic scenario through the authorized test-data path; and
7. assert create/retrieve/export size and duration metrics, denial count,
   correlation response/propagation, and no sensitive metric labels.

This path must not call an LLM, job provider, Stripe, email, analytics, or a
real user account. It must not be scheduled on a paid service without separate
approval. Retain only aggregate results, the safe correlation ID, build commit,
environment name and timestamps.

## Incident runbook

### 1. Protect user data and bound impact

1. Confirm the alert from a second aggregate signal.
2. Record UTC start time, commit/deployment identifier, environment, affected
   bounded operation, status/reason and safe correlation IDs.
3. Never copy document content, filenames, raw IDs, tokens or storage details
   into chat, tickets, issue comments or dashboards.
4. If write integrity is uncertain, stop new document writes at the approved
   gateway boundary. Preserve reads only when readiness and ownership controls
   remain trustworthy.
5. Do not delete, repair or restore records until the operation-state owner has
   classified the failure.

### 2. Diagnose by stable reason

| Symptom | Safe checks | Response |
| --- | --- | --- |
| `authentication_required` spike | Token issuer/audience configuration, JWKS reachability, recent credential rollout | Roll back the configuration/consumer release or rotate through the approved secret procedure; never print tokens |
| `access_denied` spike | Service role and route matrix, owner-context rollout, recent client release | Correct least-privilege routing; do not weaken ownership checks |
| Readiness down / `dependency` failures | Database health, connection-pool saturation, platform events, storage health | Stop writes, restore dependency availability, then prove readiness and synthetic write/read |
| `invalid` failures | Aggregate operation/type and release change | Reproduce with synthetic data; do not capture the rejected body or filename |
| `not_found` increase | Consumer release, lifecycle/retention event counts, reconciliation aggregate | Verify owner-scoped contract and operation state; do not enumerate identifiers |
| Latency or payload alert | Payload distribution, pool/thread saturation, database/storage latency | Apply bounded traffic control or rollback; content-limit work remains DOC-04 |
| Reconciliation repair/failure | DOC-08 operation state, aggregate anomaly reason, last known good deployment | Quarantine uncertain writes and follow the safeguards below |

### 3. Reconciliation safeguards

The current merged repository records when an activation/replacement repairs
multiple active versions. DOC-08's feature branch owns durable operation
journals, orphan/stuck-state detection, scheduled/manual reconciliation and
safe compensating actions.

Until DOC-08 is integrated:

- treat any repeated repair as evidence of a consistency incident;
- do not infer ownership or delete an orphan from a filename, timestamp or
  storage path;
- do not choose a winning version solely because it is newest;
- preserve metadata and binary evidence, stop affected writes, and escalate;
- record a reconciliation failure metric only from a bounded approved path;
  and
- require a reviewed query/plan plus backup evidence before manual mutation.

### 4. Backup, restore and recovery

DOC-03 provides repository-level PostgreSQL, object-storage, checksum,
migration and synthetic backup/restore evidence. Infrastructure still owns
deployed private-bucket, managed-key, credential, versioning and recovery
proof. For an approved deployed store:

1. stop writes and identify the last trustworthy operation/recovery point;
2. restore into a new isolated target, never over the only failed copy;
3. validate migrations, encryption, ownership queries and storage checks;
4. run the synthetic write/read/export path without real documents;
5. reconcile metadata/binaries using DOC-08 operation state;
6. switch traffic only after readiness and integrity evidence pass; and
7. retain redacted recovery point, duration, RPO/RTO result and approver.

Never place backup locations, database URLs, bucket names, keys or credentials
in the incident record.

### 5. Privacy-safe support evidence

Allowed evidence:

- safe correlation ID and UTC time window;
- environment and immutable application/deployment commit;
- endpoint family, method, HTTP status and bounded operation/reason/type;
- aggregate count/rate/latency/size distribution;
- liveness/readiness state and fixed dependency component state; and
- synthetic scenario result.

Forbidden evidence includes document screenshots/content, personal filenames,
raw identifiers, request/response bodies, tokens, SQL/bind values, exception
messages containing connection details, or direct production database queries
copied into a ticket.

### 6. Recovery and closure

1. Require readiness to remain healthy and alert rates to remain below
   thresholds for at least 15 minutes.
2. Re-run the synthetic path and the affected deterministic failure test.
3. Confirm no uncertain operation remains unowned.
4. Record impact in aggregate, corrective commit/configuration, rollback
   readiness and follow-up issue links.
5. Notify the security/privacy owner immediately if forbidden data entered a
   log, metric, ticket or support channel; preserve evidence and follow the
   approved breach procedure.

## Executable runbook evidence

Local Maven verification exercises:

- bounded success/failure/duration/payload metrics and failure classification;
- access-denied and reconciliation metrics;
- collapse of arbitrary dimensions plus log/metric redaction;
- service-level redaction with private-looking synthetic values;
- inbound response/MDC and outbound HTTP correlation propagation;
- readiness `UP`, invalid connection and exception paths with redacted details;
- authenticated create/retrieve/export and denied-request metric integration;
  and
- this document's required catalogue, thresholds, synthetic, recovery and
  privacy sections.

These tests prove repository behavior only. DOC-08, DOCGEN-19 and
Infrastructure must retain integrated reconciliation, deployed dependency,
dashboard, alert, recovery and cross-service drill evidence before DOC-10 can
be considered fully enabled.

# ts15-quarkus-banking

Reference project for [gherkin-ai](https://github.com/FennerEduardo/gherkin-ai-cli) 3.2.1. A BIAN Payment Execution microservice in Quarkus 3.40 LTS. It follows the `banking` industry profile (BIAN Service Landscape and ISO 20022) and publishes to Google Cloud Pub/Sub.

The project was created with the CLI from start to finish (`ghk init --enterprise --industry banking`, `ghk lint`, `ghk generate`). Everything in `generated-specs/` was generated from the specification in `features/payment_execution.feature`. The project builds, its generated tests pass, and its runtime integration suite passes against the Pub/Sub emulator, as verified on 2026-10-25 (see [Verification](#verification)).

## Stack

| Part | Technology |
|---|---|
| Backend | Java 21, Quarkus 3.40 LTS (Maven): Quarkus REST, Agroal + PostgreSQL JDBC, scheduler, SmallRye Health (tier `stable`) |
| Runtime kernel | PostgreSQL command service with idempotency and transactional outbox, outbox relay and inbox consumer with dead-letter queue over Google Cloud Pub/Sub (`stack.messaging: "pubsub"`) or RabbitMQ, saga orchestrator, OpenTelemetry trace propagation |
| Application layer | `POST /api/v1/payment-execution/{id}/{command}` on the runtime command service; the tenant comes only from the validated JWT (`tenant_id` claim) |
| Industry profile | `banking`: BIAN Service Landscape 12.0 and ISO 20022 (`industry.id` in `gherkin-ai.config.json`) |
| Architecture (configuration) | `hexagonal` |

## Industry profile

The `banking` profile adds deterministic lint rules to `ghk lint`, and rules that the AI agent must follow to the generated prompts:

| Rule | Checks |
|---|---|
| GHK101 | The feature declares its BIAN Service Domain (`@bian:PaymentExecution`) |
| GHK102 | One BIAN Service Domain per feature (an internal API serves a single domain) |
| GHK103 | A feature that moves money covers a duplicated request (same idempotency key) |
| GHK104 | ISO 20022 / BIAN vocabulary (Debtor and Creditor, not payer and payee) |

`ghk generate` writes the profile's guidance for agents to `.ghe/industry-context.md` and `.json` (not committed; they are regenerated). It also adds `x-industry` to the OpenAPI and AsyncAPI contracts (standards and the `@bian`, `@iso20022` and `@capability` classification) and an "Industry Standards" section to the ADR. Show the full profile with `ghk industry show banking`.

## Specification

**Ejecución de pagos entre cuentas (BIAN Payment Execution)** (`features/payment_execution.feature`, Gherkin dialect `es`; tags `@aggregate:PaymentExecution @command:ExecutePayment @event:PaymentExecuted @bian:PaymentExecution @iso20022:pacs.008 @capability:L3`)

> Como Debtor de una cuenta corriente
> Quiero ejecutar una transferencia hacia un Creditor
> Para que el pago se liquide una sola vez y quede trazado con ISO 20022

Scenarios:

1. Ejecución de un pago aceptado
2. Solicitud duplicada con la misma llave de idempotencia
3. Solicitud sin un token de acceso válido
4. Pago rechazado por fondos insuficientes

With the banking rules active, `ghk lint` scores it 99/100. `ghk converge` gives 97%, above the 80% threshold of the pull-request gate. Commands are not inferred from Spanish steps, so the feature declares them with `@command:ExecutePayment` and `@event:PaymentExecuted` (lint rule GHK016). The generated API exposes `execute_payment` instead of a generic `process_payment_execution`, and traceability reaches 100%.

## Repository layout

| Path | Content |
|---|---|
| `features/` | The executable specification (source of truth) |
| `gherkin-ai.config.json` | Stack, industry and generation settings used by `ghk` (written by `ghk init`) |
| `generated-specs/` | The generated project: domain kernel, runtime, application layer, contracts, tests and agent prompts |
| `generated-specs/Dockerfile`, `.dockerignore` | Production image (multi-stage, non-root) with liveness and readiness probes |
| `generated-specs/contracts/payment-execution/` | OpenAPI, AsyncAPI and ADR of the feature (with `x-industry`) |
| `.gherkin-ai/constitution.yaml` | Enterprise constitution (`ghk init --enterprise`): architecture, stack, security and the banking compliance frameworks that agents and `ghk converge` check against. The rest of `.gherkin-ai/` (credentials, logs) is ignored |
| `.ghkgovernance.yaml` | Agent governance policy (read by the gherkin-ai agent firewall) |
| `.github/workflows/gherkin-ai-gate.yml` | Pull-request quality gate (`ghk lint` and `ghk converge`, CLI version pinned) |

## Build and test

The commands below are the ones gherkin-ai uses to verify this stack. Run them in `generated-specs` with the toolchain installed, or inside the official image `maven:3.9-eclipse-temurin-21`.

```bash
cd generated-specs
mvn -q -B -ntp -DskipTests package
mvn -B -ntp test
```

Runtime integration suite (IT1–IT7: transactional outbox, idempotency under concurrency, exactly-once relay, tenant isolation, saga compensation, inbox deduplication and dead-lettering, trace propagation), plus an HTTP test of the generated API (tenant from the JWT, idempotency key, outbox). Against the Google Cloud Pub/Sub emulator, the broker of this configuration:

```bash
docker run -d --name ghk-pg -e POSTGRES_PASSWORD=ghk -p 5432:5432 postgres:17-alpine
docker run -d --name ghk-pubsub -p 8085:8085 gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators \
  gcloud beta emulators pubsub start --host-port=0.0.0.0:8085 --project=local-project
export DATABASE_URL=postgres://postgres:ghk@localhost:5432/postgres
export GHK_BROKER=pubsub PUBSUB_EMULATOR_HOST=localhost:8085 PUBSUB_PROJECT_ID=local-project
mvn -B -ntp test -Pintegration
```

The same suite runs against RabbitMQ with `AMQP_URL` set and `GHK_BROKER` unset (or `GHK_BROKER=rabbitmq`).

For local development, `docker compose up` starts PostgreSQL and the Pub/Sub emulator with the variables of `.env.example`.

## Production image and cloud deployment

`generated-specs/` has a production `Dockerfile`: Maven build, then the Quarkus fast-jar on `eclipse-temurin:21-jre` as a non-root user. The image is configured only through environment variables, so the same image runs on AWS ECS Fargate, Google Cloud Run, Azure Container Apps or Kubernetes. It listens on port 8080, with liveness probe `/q/health/live` and readiness probe `/q/health/ready`.

```bash
cd generated-specs
docker build -t ts15-quarkus-banking .
docker run --rm -p 8080:8080 \
  -e DATABASE_URL=postgres://postgres:ghk@host.docker.internal:5432/postgres \
  -e GHK_BROKER=pubsub -e PUBSUB_PROJECT_ID=local-project -e PUBSUB_EMULATOR_HOST=host.docker.internal:8085 \
  -e JWT_SECRET='change-me-to-a-secret-of-32-chars!' \
  ts15-quarkus-banking
curl -f http://localhost:8080/q/health/live && curl -f http://localhost:8080/q/health/ready
```

The runtime kernel chooses its event broker when it starts: `GHK_BROKER` if set, otherwise the broker whose variables are present, otherwise `stack.messaging` (here `pubsub`).

| Cloud | Broker | Variables |
|---|---|---|
| GCP | Pub/Sub | `GHK_BROKER=pubsub`, `PUBSUB_PROJECT_ID`; the token comes from the workload identity (no `PUBSUB_EMULATOR_HOST`) |
| AWS | Amazon MQ for RabbitMQ | `AMQP_URL` (the broker's `amqps://` endpoint) |
| Azure | Service Bus | Generate with `stack.messaging: "servicebus"` (adds the Azure SDK), then `SERVICEBUS_NAMESPACE` with a managed identity |

The full list of variables and the per-cloud services are in the [deployment guide](https://github.com/FennerEduardo/gherkin-ai-cli/blob/main/docs/DEPLOYMENT.md).

## Regenerate

```bash
npm install -g gherkin-ai@3.2.1
ghk lint -f features/payment_execution.feature
ghk generate -f features/payment_execution.feature
ghk converge -f features/payment_execution.feature     # specification-to-code convergence
```

The project was created with:

```bash
ghk init --enterprise -p ts15-quarkus-banking -a hexagonal --industry banking -l java -f quarkus -o jdbc -d postgresql \
  -v jakarta-validation -m pubsub -t junit --outputDir ./generated-specs
```

Regeneration replaces only files that are unchanged since they were generated. An edited file is kept, and the new version is written next to it as `<file>.ghk-new`. Review the diff before committing.

## Verification

Verified on 2026-10-25 with gherkin-ai 3.2.1, in the official Linux images (Docker):

| Check | Image | Result | Time |
|---|---|---|---|
| Quarkus 3.40 LTS (Java 21): build, tests and runtime integration against the Pub/Sub emulator (IT1–IT7 and the HTTP API) | `maven:3.9-eclipse-temurin-21` | pass | 281 s |
| Production image: `docker build`, start against a fresh PostgreSQL database (the service creates its tables) and the Pub/Sub emulator, liveness `/q/health/live` and readiness `/q/health/ready` answer 200 | `Dockerfile` | pass | included above |

CLI checks with 3.2.1: `ghk lint` 99/100 (banking rules active), `ghk validate` passes, and `ghk converge` 92% (specification quality, scenario coverage, contracts, architecture policy and traceability; run it for the breakdown).

The same recipes run in the gherkin-ai CI for every supported stack (golden `quarkus-pubsub`). The method and the full results are in the [evaluation report](https://github.com/FennerEduardo/gherkin-ai-cli/blob/main/docs/EVALUATION_REPORT.md).

## License

MIT. Generated with [gherkin-ai](https://www.npmjs.com/package/gherkin-ai), published on npm by the [fenner](https://www.npmjs.com/org/fenner) organization.

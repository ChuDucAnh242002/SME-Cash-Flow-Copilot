# SME Cash-Flow Copilot

A cash-flow planning workspace for Finnish SMEs: see when cash runs short, explain
which obligations cause it, and explore payment assumptions without changing bank facts.

**Independent prototype, not a Nordea product. No real money moves.**
Java 21 / Spring Boot, React / TypeScript, PostgreSQL, Kafka, Docker Compose, Kubernetes.

## Try the demo

Requires Node 22.12+, Docker Engine with modern Compose, about 4 GB free memory,
and internet access for the first build. From the repository root:

```sh
node scripts/demo.mjs up --build
```

Open **http://localhost:8083** with **developer / local-demo-password**.
No bank credentials, portal subscription, or `.env` required. With remote VS Code,
forward port 8083. Wait for `Demo ready`.

The synthetic baseline has six documents, three bank transactions, **EUR 12,500**
booked balance, and a **EUR -2,100** projected minimum. Rescheduling the equipment
expense illustrates how a timing assumption can remove that shortage.

```sh
node scripts/demo.mjs verify                 # Check the clean baseline
node scripts/demo.mjs stop                   # Keep data for later
node scripts/demo.mjs up                     # Resume
node scripts/demo.mjs reset --confirm-reset  # Delete ONLY dedicated demo data
```

The demo has separate volumes from statement/sandbox workspaces. Do not put real
business data in it or expose its known credentials publicly. Verification syncs
synthetic facts; after edits, reset before expecting baseline totals.
See the [five-minute walkthrough](docs/demo.md) for the exact presentation sequence.

## What it demonstrates

- **Explainable planning:** 30/60/90-day daily forecasts, shortage contributors,
  expected payment dates, and customer-delay scenarios.
- **Facts versus assumptions:** a saved planning balance never overwrites the booked
  balance; historical transactions are not added to it again.
- **Human-in-the-loop reconciliation:** reference/direction matching, partial
  settlement, manual linking, explicit unmatched holds, and recorded review reasons.
- **Monthly obligations:** anchored monthly expenses generate ordinary editable
  documents; stopping a rule retains already generated obligations.
- **Multiple read-only sources:** credential-free demo, statement CSV preview/confirm,
  or Nordea's Finnish Instant Reporting sandbox, in separate workspaces.
- **Resilience:** durable outbox, idempotent consumer, visible retry/backlog state,
  authenticated metrics, and verified Kubernetes database backup/restore.

## Architecture

One Spring Boot application serves the React bundle and API. PostgreSQL is the
authoritative store; financial mutations and outbox events commit together. Kafka
triggers reconciliation, whose processed marker and allocations commit atomically.
The dashboard computes its forecast from current database state.

This is a **modular monolith with a bank port/adapters**, not microservices, event
sourcing, or full CQRS. Kafka delivery is at least once, not an exactly-once banking
guarantee. Safe edits and manual reviews reconcile synchronously as well.

- [Architecture and event sequence](docs/architecture.md)
- [Four architecture decision records](docs/decisions.md)
- [Source entry points](docs/architecture.md#source-map)

## Kubernetes

[Base manifests](deploy/k8s) and a [synthetic demo overlay](deploy/k8s-demo) support
Kubernetes with dynamic PVC storage. Import the app image into every schedulable
containerd worker or use a private registry. One app replica and one PostgreSQL/Kafka
instance are deliberate prototype limits, not high availability.

See [deployment instructions](docs/reference.md#kubernetes) and
[backup/restore procedures](docs/reference.md#development-backup-and-restore).
No public Ingress is installed. Local-path storage survives pod restarts but does not
provide transparent recovery from a lost node/disk.

## Evidence and boundaries

Steps 5-6 verified **39 backend tests**, **15 frontend unit tests**, and **21 demo
browser tests** on the Kubernetes deployment. Backend integration tests use PostgreSQL
Testcontainers and embedded Kafka. CLI coverage includes **nine backup/restore tests**.
Recovery checks exercised broker outage plus app restart, exact database fingerprints
across PostgreSQL/Kafka pod recreation, and replay after restoring behind Kafka offsets.

[Test commands and scope](docs/reference.md#tests-and-development) distinguish checked
behavior from production guarantees. There is no benchmark, HA certification, or
general accessibility certification.

Scope: **one owner, one workspace, one Finnish EUR account**. No payment initiation,
generative AI, tax calculation, automatic recurrence inference, multi-tenancy,
transaction splitting/refunds, or production banking authorization. Forecasts assume
entered documents are complete and customers pay as modelled; they are not financial
advice or intraday liquidity guarantees.

## Documentation

| Start here | Details |
|---|---|
| [Recruiter walkthrough](docs/demo.md) | Synthetic baseline, scenarios, reset, failure recovery |
| [Architecture](docs/architecture.md) | Boundaries, transaction sequence, invariants, source map |
| [Decisions](docs/decisions.md) | Rationale, alternatives, consequences |
| [Workflow/operations reference](docs/reference.md) | CSV contracts, payment review, recurring rules, tests, API |
| [Nordea sandbox guide](docs/nordea-sandbox.md) | Portal onboarding, credentials, verification limits |

### Reliability and processing diagnostics

[Queue semantics, metrics, and operator recovery](docs/reference.md#reliability-and-processing-diagnostics).

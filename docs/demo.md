# Repeatable recruiter demo

Independent prototype, not a Nordea product. Everything in this workspace is synthetic.
No bank credentials, production access, payment initiation or real money.

## Start, pause and reset

Requirements: Node 22.12+, Docker Engine with Compose supporting `!override`/`!reset`,
approximately 4 GB free memory, and internet access for the first image build.
Run from the repository root:

```sh
node scripts/demo.mjs up --build
```

Open **http://localhost:8083**, using **developer / local-demo-password**.
For remote VS Code, forward port **8083** through the Ports panel.
Wait for the CLI to print `Demo ready` before starting the walkthrough.
The first launch seeds six documents, syncs three synthetic bank transactions, and
waits for Kafka reconciliation. No portal subscriptions or `.env` are needed.

```sh
node scripts/demo.mjs status
node scripts/demo.mjs stop                 # Data retained
node scripts/demo.mjs up                   # Resume existing session
node scripts/demo.mjs reset --confirm-reset # Delete demo data and seed again
node scripts/demo.mjs verify               # Check the baseline before presenting
node scripts/demo.mjs check-recovery --confirm-outage # Interrupt demo broker/app, verify recovery
```

Use `up --build` after changing source code. Reset reuses the built image unless
you add `--build`. A reset without `--confirm-reset` fails before contacting Docker.
`up` preserves existing documents, reviews, planning overrides and recurring rules.
`verify` runs the existing HTTP smoke checks; it seeds an empty workspace and syncs
synthetic bank facts, so it is a baseline check, not a read-only health probe.
After making demo edits, `verify` may fail intentionally: reset before presenting again.
If startup or baseline verification fails, the command exits nonzero; inspect its
error instead of assuming the demo is ready. `status` shows container health.
The recovery check is optional and takes several minutes. It verifies a saved outbox
event survives app restart with Kafka paused (DNS still resolvable), then restores the broker and checks automatic
reconciliation. It preserves plans and decisions, and restores Kafka even after an
ordinary check failure. A killed process cannot run cleanup: run
`docker unpause sme-cashflow-recruiter-demo-kafka-1` if paused, then use `up` to restore the
stack. Cold startup with an unresolvable broker DNS name requires restoring Kafka first.
See [processing diagnostics and recovery](reference.md#reliability-and-processing-diagnostics).

Isolation is fixed in the CLI:

- Compose project: `sme-cashflow-recruiter-demo`.
- Image: `sme-cashflow-recruiter-demo:local`, separate from the statement/sandbox image.
- Config: [compose.yaml](../compose.yaml) plus [compose.demo.yaml](../compose.demo.yaml).
- Port: `127.0.0.1:8083`; no public binding.
- Database/Kafka volumes belong only to this project. Reset deletes them, including
  reviews, generated occurrences, planning overrides and event history, then reseeds.
- No local `.env`, Nordea credential mounts or inherited `COMPOSE_*` overrides.
  CLI checks ownership/configuration before operating on existing named resources.
  It does not stop, start, rebuild or delete your statement or sandbox projects.

Do not store actual business data in this disposable workspace or expose its known
credentials publicly. This is a local development/demo setup, not a public deployment.
Do not change its project name or attach external volumes; the safeguards assume the
provided configuration. The user-managed statement workspace on port 8081 is separate.

## Five-minute walkthrough

Reset just before the demonstration. Dates below are relative to the Helsinki day
the scenario is seeded; reset again if you have kept it across several days.

### 1. Start with facts (45 seconds)

Point out **DEMO DATA** and the synthetic-data notice. The booked balance is
**EUR 12,500**, there are **six documents**, and the three transactions include
**two matched payments** and one unmatched **EUR -89 subscription**.

Explain that historical payments are already reflected in the booked balance.
They reduce unpaid invoice/expense amounts through reconciliation; they are not
added to the opening balance again.

### 2. Explain a shortage (60 seconds)

At 30 days and customer delay **On time**:

| Figure | EUR |
|---|---:|
| Opening balance | 12,500 |
| Expected receipts | 10,900 |
| Planned expenses | 14,600 |
| Closing balance | 8,800 |
| Lowest balance | -2,100 |

The first shortage is day **+9**: salaries of **9,800** at day +5 leave **2,700**,
then equipment of **4,800** brings the balance to **-2,100**.
Expand **Documents through the first shortage** to show these actual contributors.
This is a daily closing model, not an intraday guarantee or AI advice.

### 3. Explore an assumption (60 seconds)

Use **Adjust expected date** for Equipment supplier, reference **20003**.
Set its expected date to **today +20 days** and save, leaving the contractual due
date unchanged. The minimum becomes **EUR 2,700**, and the shortage disappears.
Set customer payment delay to **7 days late**: the EUR 8,500 receipt moves to day +21,
so equipment on day +20 reintroduces the **EUR -2,100** shortage.
Restore **On time**. A later expected date is an assumption, not permission to pay late.

### 4. Review a payment (60 seconds)

Add an expense: supplier **Software subscription**, reference **SUB-DEMO**,
amount **89**, due **today**. Open **Bank transactions**, review `demo-unmatched-1`,
and manually link the whole payment to that expense with reason
**Demo: subscription confirmed against supplier record**.

Show the **manual match** and its audit history. The expense's remaining amount is
zero; the booked balance and historical transaction are unchanged. Explain that
unlinking holds a payment unmatched until automatic matching is explicitly resumed.

### 5. Show recurring obligations and the architecture (75 seconds)

Open **Monthly recurring expenses → Add monthly expense**.
Create **Demo insurance**, **EUR 300**, first due **today**, no end date.
Show the generated documents and their monthly provenance/unique references.
The rolling window covers 90 calendar dates; the number of occurrences depends on
month lengths. Edit individual occurrences through the normal document workflow.

Stop the rule and confirm. Existing future expenses remain; stopping prevents new
generation, it does not erase obligations or matches.

Finish with the [architecture](architecture.md) and [decision records](decisions.md):
Spring Boot modular monolith, ports/adapters, PostgreSQL, transactional outbox,
at-least-once Kafka and idempotent reconciliation. Kubernetes manifests provide a
single-broker development deployment on Kubernetes, not a claim of production readiness.
Forecasts are deterministic Java calculations; no generative AI or real payments.

## After presenting

```sh
node scripts/demo.mjs stop
```

This leaves the data for inspection. Before the next presentation, reset and verify:

```sh
node scripts/demo.mjs reset --confirm-reset
node scripts/demo.mjs verify
```

For frontend/browser verification, use the same credentials and target:

```sh
node --test scripts/demo.test.mjs
APP_URL=http://127.0.0.1:8083 node scripts/smoke.mjs
cd frontend
APP_URL=http://127.0.0.1:8083 npm run test:e2e
```

Browser tests preserve the baseline forecast but may retain cancelled test documents
and stopped rules. Reset again before expecting exact baseline document counts.

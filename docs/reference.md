# Workflow and operations reference

Detailed contracts and runbooks for SME Cash-Flow Copilot. Start with the
[project overview](../README.md), [demo walkthrough](demo.md), or
[architecture](architecture.md). Run CLI examples from the repository root unless
a command explicitly changes directory. Independent prototype; no real money moves.

## Contents

- [Local startup and document planning](#run-locally)
- [Nordea sandbox](#nordea-sandbox-setup)
- [Statement preview and import](#bank-statement-csv-workspace)
- [Monthly expenses](#monthly-recurring-expenses)
- [Forecast explanations](#explain-a-cash-shortage)
- [Payment review](#payment-reconciliation-review)
- [Processing diagnostics and recovery](#reliability-and-processing-diagnostics)
- [Kubernetes and backups](#kubernetes)
- [Tests and development](#tests-and-development)
- [Troubleshooting](#operations-and-troubleshooting)
- [API contracts](#api)

## Run locally

For a recruiter walkthrough or repeatable synthetic session, use the
[isolated demo guide](demo.md). It starts a credential-free workspace on
**http://localhost:8083**, ignores your `.env`, and offers an explicitly confirmed
reset without touching statement/sandbox data:

```sh
# Node 22.12+ and Docker Compose required for this CLI.
node scripts/demo.mjs up --build
# Later: stop/resume without losing edits.
node scripts/demo.mjs stop
node scripts/demo.mjs up
# Restore the synthetic baseline (deletes ONLY the dedicated demo's data).
node scripts/demo.mjs reset --confirm-reset
```

The original manual startup below remains available on port 8080.

Requires Docker Engine with Compose, approximately 4 GB available memory, and outbound
access to Maven Central, npm and image registries. Java and Node are not required on the host.

```sh
cp .env.example .env
# Optionally change APP_PASSWORD and POSTGRES_PASSWORD before the first start.
docker compose up --build -d --wait
```

Open **http://localhost:8080**. Your browser asks for HTTP Basic credentials:
`developer` / `local-demo-password` by default (or your `.env` values).
If VS Code is connected to this machine over SSH, forward port **8080** from the
Ports panel and open its forwarded address on your own computer.

1. Click **Sync bank account**.
2. Click **Load demo scenario**.
3. Wait until the pending-event indicator disappears (usually a few seconds).
4. Explore the forecast, partial reconciliation and payment-delay scenarios.

The demo has a EUR 12,500 booked balance, two historical matched documents, one
unmatched subscription, and a projected EUR -2,100 minimum. Dates of planned demo
items are relative to the day you load them; bank transaction IDs/dates are fixed
to make repeated syncs idempotent. Historical transactions are already reflected in
the booked balance and are **not** added to it again.

The app is bound to **127.0.0.1**, not a public interface. Do not expose the demo
credentials publicly. Volumes persist across `docker compose down`; restart with
`docker compose up -d`. To stop without removing data:

```sh
docker compose down
```

### Planning scenarios and document changes

Set **Planning opening balance / EUR** and click **Save planning balance** to explore
a realistic SME scenario, even when Nordea's sandbox balance is huge. The override:

- Is saved in PostgreSQL for the workspace and survives reloads, restarts and bank syncs.
- Is visibly labelled **Planning scenario / not a bank balance**.
- Does not change the bank-reported balance, transactions or payment matches.
- Works without a bank connection. Zero and negative opening amounts are valid.
- Can be removed with **Use bank balance** (or **Clear planning balance** if unsynced).

Without either a bank snapshot or an override, the UI does not present the computed
zero-based series as a usable forecast. Setting an override does not synthesize transactions.
Forecasts still use actual matched payments to calculate unpaid document portions.

Use **Edit** on a document to change its details. **Expected payment date** is optional:
the forecast uses that date instead of the due date, then applies the customer-delay
scenario to invoices only. Clearing it restores the due-date assumption. The contractual
due date is preserved, so an unpaid item can remain **Overdue** even with a later expected
date. Document edits are saved workspace-wide, not as multiple independent scenario copies.

Cancellation requires confirmation, is irreversible in this preview, excludes the item
from forecasts/totals and retains it in the document list. Cancelled references cannot
be reused, and cancelled items never match future bank transactions. Documents with
matched payments cannot be cancelled; their type/reference are locked and their amount
cannot be reduced below matched payments. You can still update dates or increase the
amount, preserving existing matches. Corrections involving refunds/reversals need a
future accounting-review workflow.

Edits/cancellation include a revision check. Stale forms receive HTTP 409 instead of
overwriting another session's changes. Close and reopen the form after refreshing.
Reconciliation runs synchronously under the workspace lock before and after lifecycle
changes, so guards also work when Kafka is behind. The same transaction appends an
outbox event; Kafka consumers can replay it idempotently.

#### Try a realistic cash squeeze

In an empty planning workspace, set an opening balance of **EUR 12,000**, then add:

| Kind | Description | Amount | Due |
|---|---|---:|---|
| Expense | Salaries | 9,800 | Today + 5 days |
| Expense | Equipment | 4,800 | Today + 9 days |
| Invoice | Customer project | 8,500 | Today + 14 days |

The minimum becomes **EUR -2,600** on day 9. Edit Equipment's **expected payment date**
to day 20; the minimum becomes **EUR 2,200**. With the customer delay set to seven days,
the shortfall returns because the receipt moves to day 21. These are hypothetical
plans, not bank data or a recommendation to delay contractual payments.
Do not load sample documents over a business's actual plans; use a separate workspace.

### CSV import

See [examples/documents.csv](../examples/documents.csv). Exact header:

```csv
kind,counterparty,reference,amount,dueDate
INVOICE,"Example Customer, Oy",30001,2500.00,2026-11-15
EXPENSE,Example Supplier Oy,40001,800.00,2026-11-10
```

UTF-8 RFC4180; at most 200 rows / 256 KB. Positive EUR amounts with at most two
decimal places; ISO dates. Past due dates are allowed. A reference must be unique
across both document types after whitespace removal and uppercasing. The entire
import rolls back on any duplicate or invalid row, including a reference retained by a
cancelled document. The five-column CSV contract is unchanged; set optional expected
payment dates through Edit after import.

## Nordea sandbox setup

See [the sandbox integration guide](nordea-sandbox.md).

The application does not ask you to paste secrets into the UI, source code or chat.
It reads ignored local files on each sync, so you can replace an expired access token
without rebuilding. Automated tests use a local HTTP server for authentication-header,
parsing, pagination and upstream-error cases; a user's first authenticated sandbox sync
remains an onboarding verification step.

Demo, Nordea and statement modes cannot share a workspace database. To preserve your demo while
starting an independent sandbox workspace:

```sh
docker compose down
# Set BANK_MODE=nordea and configure credentials in .env / secrets/.
# A distinct project name gives the new workspace separate database and Kafka volumes.
docker compose -p sme-cashflow-sandbox up --build -d --wait
```

Stop one workspace before starting the other on the same APP_PORT. Mode and account
changes are guarded server-side. Do not delete a database just to switch accounts.

## Bank-statement CSV workspace

Statement mode is an **isolated, user-uploaded EUR account**, not another Nordea
connection. It uses the same documents, forecast engine, transactional outbox and Kafka
reconciliation as bank mode. Uploaded facts are labelled **not bank-verified**.
Bank sync and demo seeding are disabled server-side; statement import is rejected
in demo/Nordea workspaces. There is no in-app workspace switching.

Keep your Nordea workspace on port 8080 and start a separate statement workspace:

```sh
docker compose -p sme-cashflow-statements -f compose.yaml -f compose.statement.yaml up --build -d --wait
# Open http://localhost:8081 using your configured APP_USER / APP_PASSWORD.
```

Always include **`-p sme-cashflow-statements -f compose.yaml -f compose.statement.yaml`**
when operating this workspace, especially if your ignored `.env` sets
`COMPOSE_PROJECT_NAME` to your Nordea project. The override pins statement mode,
uses independent project-scoped PostgreSQL/Kafka volumes, binds only to localhost
and does **not mount Nordea credential files**. Your `.env` and Nordea containers
are not changed. Override `STATEMENT_APP_PORT` if 8081 is occupied. A recent Docker
Compose supporting `!override` and `!reset` is required (2.24.4+).

### Preview, then confirm

1. Choose a stable local account ID (for example `company-eur`) and account name.
   The first confirmed import binds this database to that ID. It is not a bank
   authorization or proof that you own an account.
2. Enter the statement's **closing balance and closing date**, not its balance
   before the listed transactions. Zero/negative closing balances are valid.
3. Upload a UTF-8 RFC4180 CSV using this exact header:

   ```csv
   transactionId,bookingDate,amount,reference,counterparty
   receipt-1,2026-10-28,400.00,STMT-INV,Example Customer Oy
   rent-1,2026-10-28,-200.00,STMT-RENT,Example Landlord Oy
   subscription-1,2026-10-28,-89.00,,Example Software Oy
   ```

4. Click **Preview statement**. Review every row's **New** or **Duplicate / skip**
   status, the account, closing balance/date and counts. No financial data is saved.
   Editing the inputs invalidates the preview; **Discard preview** saves nothing.
5. Confirm that the closing balance already includes the booked payments, then
   click **Confirm statement import**. All new rows, the balance and an outbox event
   are saved atomically. Wait for pending Kafka reconciliation events to reach zero.
6. Add invoices/expenses with matching references. Full/partial payments reduce
   their unpaid portions; missing references and excessive payments remain **Needs review**.

The supplied [synthetic example](../examples/statement.csv) is for a disposable workspace,
not for mixing into your actual business data. With a EUR 5,000 closing balance, an
invoice `STMT-INV` of EUR 1,000 due today + 5 days and expense `STMT-RENT` of EUR 200,
the example receipt matches EUR 400 and rent settles EUR 200. Only the remaining
EUR 600 invoice is forecast: final balance **EUR 5,600**, not EUR 5,711.
Historical transactions are already included in the closing balance and are not added again.

### Import contract and limits

- One EUR account per database. Account IDs use 1–128 ASCII letters/digits or `._:-`.
  This is a normalized format; automatic bank-specific CSV mapping is not included.
- At most 500 rows / 256 KB. Transaction IDs are required (max 128 characters),
  counterparties required (max 160), references optional (max 80). Quote fields
  containing commas. Dates are ISO `YYYY-MM-DD`; amounts are nonzero signed EUR
  decimals, up to 9 integer digits and 2 decimals. No exponents, symbols or grouping
  separators. Positive means incoming, negative means outgoing.
- Use stable transaction IDs from your export; do not generate IDs from row position
  or filename. Identity is account ID + transaction ID. Changing an ID can create
  a duplicate payment: the application cannot infer that two different IDs mean one payment.
- Re-uploading identical IDs/details skips duplicates, including overlapping statements.
  Any stored ID with changed date, amount, reference or counterparty rejects the entire
  file. Repeated IDs within one file are rejected, even if identical.
- No future closing dates, no transaction after its closing date, and no older
  closing balance replacing a newer one. A different balance for the same closing
  date is rejected for review. Later closing dates may replace the balance, retaining
  prior transaction history. The opening balance before the export is unknown, so
  CSV totals are not independently validated against the declared closing balance.
- Confirmation revalidates the file under the workspace lock. A revision from preview
  prevents confirming after another import; HTTP 409 requires a new preview.
- The closing balance becomes today's forecast opening unless a saved planning
  override exists. Imports never overwrite that override. The closing date stays
  visible: activity after that date is unknown, not automatically projected.
- Parsed transactions and balance/date are persisted; the original upload is not
  retained as a file. Real statements contain sensitive information: keep them
  outside the repository and never commit them. This is not a production banking or
  accounting integration; reversals and correcting stored facts require future review tooling.

To stop without losing statement data:

```sh
docker compose -p sme-cashflow-statements -f compose.yaml -f compose.statement.yaml down
```

## Monthly recurring expenses

Open **Monthly recurring expenses → Add monthly expense**. Enter a supplier/description,
positive monthly EUR amount, first due date and optional inclusive last due date.
First due dates must be today or within five years; add historical expenses individually.
Only monthly **expenses** are supported—not recurring invoices, weekly schedules,
automatic tax calculations, or inferred obligations.

- Generation immediately creates ordinary expense documents from the first due date
  through **today + 89 days**, covering the longest forecast horizon. A scheduler extends
  this rolling window every minute and catches up missed months after downtime. Newly
  generated overdue amounts land today in the forecast, like other overdue documents;
  review missed obligations rather than assuming they were paid.
- The original day is retained: January 31 → February 28/29 → March 31. An April 30
  start means May 30, not May 31. An end date includes only occurrences due on/before it.
- Every occurrence has a unique `REC-<rule UUID>-YYYY-MM` reference and a persisted
  rule/month identity. Retries, concurrent generators, restarts, changing due dates
  and cancelling individual occurrences do not generate that month twice.
  Do not reuse the `REC-` reference prefix for unrelated documents.
- A create request includes a client-generated `requestId`. Repeating the same ID/details
  succeeds without creating another rule; different details with that ID return 409.
  Different IDs mean different rules: check for pre-existing plans to avoid modelling
  the same rent/salary twice. There is no semantic duplicate-obligation detection.
- Generated documents are labelled **Monthly occurrence** and use existing edits,
  expected dates, cancellation and payment review. Their type stays EXPENSE. A unique
  real payment reference may be entered per occurrence; a bank's repeated monthly
  reference needs manual linking to the correct occurrence.
- **Stop rule** requires confirmation and a revision. It stops only future generation,
  retaining **all** existing occurrences, including already generated future expenses.
  Cancel unwanted unpaid occurrences individually. Paid cancellation/amount safeguards
  remain enforced. Stop is irreversible in this version; series-wide edits/resume are
  deliberately deferred.
- Generation, cursor updates and an outbox event commit atomically under the workspace
  lock. Reference conflicts roll back the entire generation and scheduled failures
  appear on the dashboard. Resolve conflicting references and allow the next retry.
  Kafka handles payment matching as before; no bank facts or planning override change.
- A rule marked **Fully generated** has reached its end date; its documents are not
  necessarily paid. Stopped/ended rules remain visible for context.

## Explain a cash shortage

**Cash breakdown** shows exact horizon-only totals:

`opening balance + expected receipts - planned expenses = projected closing balance`

These are unpaid active documents scheduled in the selected horizon, after expected-date
and invoice-delay assumptions. The all-dates invoice/outgoing cards can include later
documents and therefore differ. Cancelled and fully paid documents are excluded.
Historical account transactions are already included in the opening balance, not added again.

When a first negative daily closing balance occurs, the explanation shows the balance
before that day, its receipts/outgoings and closing balance. Expand **Documents through
the first shortage** for earlier movements and first-shortage-day documents. This is a
cumulative explanation, not a claim that one invoice alone caused a shortage. **Adjust
expected date** opens the actual document form; edits save workspace-wide and leave the
contractual due date unchanged unless you explicitly edit it.

An already-negative opening is identified separately—even if today's receipts recover
the closing balance. The first-shortage closing balance may differ from the forecast
minimum reached later. These are daily closing projections, not intraday liquidity
guarantees or permission to delay contractual payments. Pending reconciliation can
change the breakdown; without a bank/statement/planning opening, it is not shown as usable.

## Payment reconciliation review

Open **Bank transactions** (or **Statement transactions**) and select **Review**.
Each transaction explains its state: automatic/manual match, missing or unknown
reference, cancelled document, direction mismatch, excess amount, zero amount,
pending reconciliation, or an explicit user decision to keep it unmatched.
An excess amount means the **whole payment** exceeds the current unpaid portion,
including amounts reserved by other payments. References are normalized as before.

### Supported corrections

- **Link payment:** choose one active invoice for an incoming payment, or one
  active expense for an outgoing payment. Only documents with enough unpaid amount
  are offered. The full payment is allocated; a smaller payment partially settles
  the document. Manual linking may override a missing/different bank reference,
  but never changes the imported transaction's reference, amount or date.
- **Remove match:** requires a reason and explicit confirmation. The document's
  paid amount is recalculated. The payment is kept **unmatched by your decision**,
  not silently rematched during the next sync, statement import, document edit or
  Kafka replay. This works for both automatic and manual matches.
- **Resume automatic matching:** explicitly releases that hold and immediately
  reruns reference matching. A match is not guaranteed. To move a currently matched
  payment to another document, remove its existing match first, then link it.

Every correction requires a nonblank reason (at most 500 characters), a preview
and confirmation. **Payment review history** records action, previous/next mode,
previous/next document ID and label snapshot, signed-in username and timestamp.
The actor comes from authentication, not the request body. History is append-only
through the application API, not a tamper-proof regulatory audit log; a database
administrator still controls stored data. Automatic matches are derived and do not
create manual audit entries. Historical labels remain as recorded if a document's
name is edited later.

Corrections reconcile synchronously under the same workspace lock used by imports
and document edits. Manual decisions reserve their document amounts before automatic
matching and survive replay and restarts. A payment revision changes when its
decision or allocated document changes; stale reviews receive HTTP 409 and must be
closed/reopened after refreshing. The dashboard also disables a review when polling
detects that its payment changed. Available amounts are checked again at confirmation,
so two sessions cannot over-allocate a document.

Correction, resulting allocations, audit entry and `PAYMENT_REVIEWED` outbox event
are saved in one transaction. Duplicate event delivery does not repeat the audit
entry. Existing matched-document edit/cancellation safeguards also apply to manual
payments. Forecasts use recalculated **unpaid portions**; balances and historical
transactions are never added again or changed by review.

**Limits:** one whole payment per document allocation; multiple payments may settle
one document. Payment splitting, overpayments, refunds/reversals and deleting
financial facts are not supported. An explicit unmatched hold can increase the
forecast's unpaid receipts/expenses: review these assumptions before relying on it.
Automatic matching still runs asynchronously for ordinary imports/document creation;
wait for pending events to reach zero before trusting totals.

## Architecture

See the [architecture overview](architecture.md) for component boundaries, the
write/reconciliation sequence, financial invariants, and explicit limitations.
The [decision records](decisions.md) explain the design trade-offs.

## Reliability and processing diagnostics

Open **Processing diagnostics** below the daily forecast. It separates events waiting
for Kafka publication from published events awaiting reconciliation, shows the oldest
pending event and last successful reconciliation, and counts failed consumer partitions.
The existing pending-event indicator remains the total **unprocessed local outbox events**.
A failing foreign/malformed Kafka record can block a partition even when that total is
zero, so consumer failures are reported independently and also shown as an alert.

Migration **V6** adds `consumer_failure`, keyed by topic/partition, with the blocking
offset, failure count and first/last failure timestamps. It contains no event payload,
exception message, account data or credentials. Existing financial records are unchanged.
Successful processing clears a failure only for the same topic/partition at that offset
or later; processing an earlier record does not claim that the failure recovered.
Failure metadata survives restarts; it describes the last observed problem, not an
active probe of Kafka connectivity.

- Consumer errors retry every three seconds, including exception types Spring Kafka
  otherwise classifies as fatal. There is **no automatic discard, offset skip or dead
  letter recovery**. Malformed/unsupported records intentionally block their partition.
- Reconciliation, the processed-event marker and clearing recovered diagnostics commit
  together. A database failure rolls them all back; retry/replay cannot duplicate an
  allocation or manual review audit entry.
- Outbox publication requires broker acknowledgement before marking the event published.
  Timeouts retain the event for retry. A crash or uncertain acknowledgement can publish
  the same event again; consumer idempotency is still required.
- Readiness means app + database availability, **not Kafka connectivity**. Saved plans
  remain accessible during a broker outage. A caught-up queue does not prove that the
  bank snapshot is fresh or that an idle Kafka connection is working.
  Cold startup still requires a resolvable Kafka bootstrap address; if Docker removes
  the broker's DNS name when its container stops, restore the broker before starting
  the app. Existing PostgreSQL facts and pending events remain durable.

### Operator recovery

1. Read the dashboard's sync/publication/consumer/recurrence errors. Do not re-import,
   reset financial data or delete outbox/processed-event rows to make warnings disappear.
2. For publication backlog, restore the broker/network and wait for automatic retry.
   For consumer failures, inspect server retry logs by **topic, partition and offset**;
   they identify the record without logging its payload in the application retry message.
3. Fix a transient database problem or deploy code supporting the expected schema, then
   let Kafka redeliver. Repeated unsupported records require operator investigation;
   this prototype provides no safe automated way to remove or skip them.
4. Verify pending stages drain, failed partitions clear, and financial matches/forecast
   remain correct. If Kafka retention expired or offsets/data were manually changed,
   investigate explicitly; the prototype has no automatic lost-event repair.

For a reproducible **synthetic-demo-only** outage and restart check:

```sh
node scripts/demo.mjs check-recovery --confirm-outage
```

It confirms the dedicated project/mode, pauses its broker (retaining DNS), syncs synthetic facts, checks
the durable publication warning, restarts the app with Kafka down, restores the broker
in `finally`, and verifies automatic recovery without changing documents, decisions,
rules or the forecast. It never resets data or targets the statement/sandbox projects.
If the process/host is forcibly killed, `finally` cannot run: use
`docker unpause sme-cashflow-recruiter-demo-kafka-1` if it is paused, then
`node scripts/demo.mjs up` and inspect diagnostics.
Run on an initialized, caught-up demo; avoid crossing Helsinki midnight during the check.

### Metrics

`GET /actuator/metrics` and individual metric URLs require the normal owner authentication.
Only minimal `/actuator/health/**` responses remain public. The application adds:

- `cashflow.events.published` — acknowledged outbox publications.
- `cashflow.events.publication.failures` — failed publication attempts saved for retry.
- `cashflow.events.processed` — newly committed reconciliations.
- `cashflow.events.duplicates` — duplicate deliveries safely ignored.
- `cashflow.events.consumer.failures` — recorded failed delivery attempts.

These are **per-process counters**, updated after database commit and reset on app
restart. They have no account/event-ID tags and are not an audit ledger. Persistent
queue/failure state comes from the authenticated dashboard, not these counters.
This uses the existing Actuator/Micrometer stack; no public metrics endpoint, Prometheus
server, tracing backend, alert routing or log aggregation is installed.

## Kubernetes

The [Kustomize manifests](../deploy/k8s) are a **development deployment**:
one application, one PostgreSQL and one KRaft Kafka broker, with persistent volumes.
They work on Kubernetes with a default dynamic storage class and may schedule across
worker nodes. They do not change your current cluster until you explicitly apply them.

```sh
export KUBE_CONTEXT="$(kubectl config current-context)"
docker build -t sme-cashflow-copilot:0.1.0 .
# For containerd workers without a registry: import on EVERY schedulable worker.
# Run once per worker using your own SSH connection and key configuration.
docker save sme-cashflow-copilot:0.1.0 | ssh ubuntu@<worker-address> 'sudo ctr -n k8s.io images import -'
# Alternatively push a versioned image to your private registry and update the
# copilot image in your Kustomize overlay (configure imagePullSecrets if needed).
kubectl --context "$KUBE_CONTEXT" apply -f deploy/k8s/namespace.yaml
# .env must contain actual APP_PASSWORD and POSTGRES_PASSWORD values.
kubectl --context "$KUBE_CONTEXT" -n sme-cashflow create secret generic cashflow-config --from-env-file=.env
kubectl --context "$KUBE_CONTEXT" apply -k deploy/k8s
kubectl --context "$KUBE_CONTEXT" -n sme-cashflow rollout status statefulset/postgres --timeout=300s
kubectl --context "$KUBE_CONTEXT" -n sme-cashflow rollout status statefulset/kafka --timeout=300s
kubectl --context "$KUBE_CONTEXT" -n sme-cashflow rollout status deployment/copilot --timeout=300s
kubectl --context "$KUBE_CONTEXT" -n sme-cashflow port-forward service/copilot 8080:8080
```

Kafka uses namespace-relative StatefulSet DNS instead of a hard-coded cluster domain.
Its headless service publishes addresses before readiness so the KRaft controller can
resolve itself during bootstrap; application readiness still depends on app + database,
not the broker.
The PVCs use the cluster's default storage class; set `storageClassName` in both
StatefulSet claim templates before first deployment if your cluster has no default.
With node-local storage, PostgreSQL and Kafka remain bound to their respective nodes:
pod restarts are supported, but losing a node/disk is not transparent failover.
For Nordea mode, create `nordea-credentials`
with keys `client-secret` and `access-token` from your local files. The developer
sandbox uses Nordea's sandbox signature marker; no private key is required.
See the integration guide. Real `cashflow-config` credentials are deliberately not
committed; only the synthetic demo overlay contains fixed local defaults.

One application replica is intentional: login/CSRF sessions are in memory. Add shared
sessions or an identity gateway before scaling replicas. No public Ingress is installed.
Application probes expose only health status without financial data.

For a **synthetic-only recruiter demo**, [the demo overlay](../deploy/k8s-demo) supplies
fixed local credentials and `BANK_MODE=demo`; never use it with business data or public
access. Build `sme-cashflow-recruiter-demo:0.1.0`, import it on every schedulable worker
as above, then apply `kubectl --context "$KUBE_CONTEXT" apply -k deploy/k8s-demo`.
Do not apply this overlay over an existing Nordea/statement workspace. The namespace is
`sme-cashflow`; Compose projects and their volumes remain independent. Forward its
service to a free local port, then run `APP_URL=http://127.0.0.1:<port> node scripts/smoke.mjs`
to initialize and verify synthetic data.

### Development backup and restore

PVCs preserve data across pod restarts; they are **not backups**.
The PostgreSQL database is authoritative. Kafka carries replayable event IDs, so a
database restore requeues outbox events that were not yet reconciled. This procedure
does not back up the Kafka PVC, provide off-site retention, or make this single-broker
deployment highly available.

Set the exact Kubernetes context explicitly for every operation; the script never
assumes or changes the current context. Backups contain sensitive financial records.
Store them outside the repository on access-restricted storage, and encrypt them before
copying off the machine:

```sh
kubectl config current-context
export KUBE_CONTEXT="<your-kubernetes-context>"
install -d -m 700 "$HOME/.local/share/sme-cashflow-backups"
scripts/k8s-db.sh backup "$HOME/.local/share/sme-cashflow-backups/cashflow-$(date -u +%Y%m%dT%H%M%SZ).dump"
scripts/k8s-db.sh verify "$HOME/.local/share/sme-cashflow-backups/cashflow-<timestamp>.dump"
```

The custom-format PostgreSQL archive and SHA-256 sidecar are mode `0600`; verification
checks both the digest and every archive entry by rendering its SQL without executing it.
This detects truncated archives but does not prove compatibility with every deployment
or replace a test restore. Back up regularly and keep multiple tested generations.
A restore replaces database contents and therefore
requires an explicit confirmation:

```sh
scripts/k8s-db.sh restore "$HOME/.local/share/sme-cashflow-backups/cashflow-<timestamp>.dump" --confirm-restore
```

Before replacing data, restore verifies the archive and writes a separate pre-restore
backup after stopping the app so accepted writes are included. It restores PostgreSQL,
queues outbox records missing a processed marker, and starts the app so Kafka can
reconcile them. If restore or app readiness
fails, it attempts to restore the pre-restore database automatically; inspect the
command output and retained backup before retrying. Do not delete the pre-restore copy
until the restored workspace has been checked. Always inspect `KUBE_CONTEXT` and the
namespace (`sme-cashflow`) before backup or restore. This is a local development
runbook, not a production disaster-recovery or compliance plan.
Use the matching application/schema version when restoring; check the dashboard's
pending stages drain and compare documents, review decisions, recurring rules and
forecast. Kafka offsets are not rolled back; republishing unprocessed outbox IDs is
intentional and idempotent. Unsupported retained Kafka records still require operator
investigation.
Run only one restore at a time and prevent concurrent deployment changes. An interrupted
process cannot guarantee cleanup: keep the printed pre-restore backup, inspect
PostgreSQL and the deployment before starting the app, and do not delete database
volumes to clear an error. Port-forward connections and in-memory login/CSRF sessions
must be re-established after an application pod is recreated.

Validate changes only in a disposable synthetic workspace:

```sh
bash -n scripts/k8s-db.sh
node --test scripts/demo.test.mjs scripts/k8s-db.test.mjs
kubectl --context "$KUBE_CONTEXT" apply --dry-run=server -k deploy/k8s-demo
# With the demo port-forward running:
APP_URL=http://127.0.0.1:8084 node scripts/smoke.mjs
cd frontend
APP_URL=http://127.0.0.1:8084 npm run test:e2e
```

Step 6 verification on Kubernetes covered the V1-V6 migrations, all 21 demo browser
tests, exact document/review/rule/transaction/outbox fingerprints across PostgreSQL
and Kafka pod recreation, exact financial output after restoring an older backup,
and automatic reconciliation of a genuinely published-but-unprocessed event after
Kafka offsets had advanced. Restore error/rollback handling is also covered by CLI
tests; corrupt or truncated archives are rejected before database replacement.

**Before production:** proper identity/tenant isolation, TLS, managed/replicated
PostgreSQL and Kafka, backups/restores, network policies, Kafka TLS/authentication,
secret management/rotation, consent lifecycle, rate-limit policies, audit retention,
accounting review workflows and Nordea's contractual/regulatory onboarding are required.

## Tests and development

### Keyboard access and interface feedback

- **Skip to main content** is the first keyboard stop, including on mobile.
- Activity tabs use Left/Right arrows (wrapping), Home/End, and a single Tab stop.
  Tab past the CSV picker to focus the active table and scroll it horizontally.
  Other wide tables have named, keyboard-focusable scrolling regions.
- Document forms focus their first editable field. Closing/saving returns focus to
  the initiating control when it still exists; cancelled/removed controls fall back
  to the add-document button. Confirmation text is focused before destructive actions.
- Payment reviews, monthly rules and statement previews move focus into the opened
  content and restore a useful keyboard position when closed. Expected-date help is
  a field description, not part of its name.
- Save progress and successful actions use polite status announcements. Failed
  actions expose an alert and focus the error while preserving the document draft.
  Dashboard refresh failures retain the last successful view and offer Retry;
  background polling does not move focus or clear unsaved form input.
  Horizon/delay changes label the previous forecast as updating until the new
  result arrives, rather than relabelling old totals as the requested horizon.
- Muted labels/table metadata have stronger contrast, focus indicators are explicit,
  and narrow screens retain controls while wide tables scroll within their panels.

The browser suite checks these specific keyboard, focus, loading/error, contrast
(4.5:1 for sampled normal-text labels) and 320px layout behaviors alongside financial
regressions. This is focused accessibility coverage, not WCAG certification or a
substitute for manual testing with screen readers across browsers.

```sh
# Node 22.12+:
cd frontend
npm ci
npm run build
npm test
cd ..

# Java 21 + Maven:
cd backend
mvn -B verify
```

Backend tests include exact forecast thresholds, CSV validation, HTTP signing,
empty continuation pages, repeated cursors, currency errors and token expiry.
Statement tests cover strict UTF-8/CSV limits, read-only previews, duplicate/conflict
handling, source/account isolation, stale confirmations, signed amounts, CSRF,
partial matching and exact forecasts without double-counting historical payments.
Payment-review tests cover reason codes, authenticated audit attribution, manual
priority, unlink/resume, stale and competing corrections, over-allocation prevention,
Kafka replay and protected document edits.
Recurring-expense tests cover short months/leap years, inclusive ends, catch-up,
idempotent/concurrent generation, retained edits/cancellations, paid guards, stopped
rules and explicit atomic generation failures. Forecast tests assert exact horizon
totals and first-shortage explanations, including a negative opening recovered today.
The integration test uses **real PostgreSQL via Testcontainers and embedded Kafka**,
covering persistence, duplicate syncs, event redelivery, full/partial reconciliation,
forecast amounts, planning overrides, expected dates, cancellation, stale-write and
matched-payment guards, authentication, CSRF and validation. Docker must be available;
the integration class explicitly skips when it is not. Unit tests still run during
the application image build.

Without host Java, run the backend suite in Maven's container:

```sh
docker run --rm --network host \
  -v "$PWD/backend:/workspace" \
  -v sme-cashflow-maven:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -B verify
```

This Linux-specific test command gives the container control over Docker; only use
trusted code. Build outputs may be owned by root when using this command.

With the demo stack running, Node can execute an HTTP smoke test:

```sh
node scripts/smoke.mjs
# Optional: stop/restart this project's Kafka and verify durable outbox recovery.
node scripts/smoke.mjs --kafka-outage
# For non-default credentials:
APP_USER=your-user APP_PASSWORD=your-password node scripts/smoke.mjs
```

It loads the demo if the workspace is empty, verifies duplicate-sync safety, waits
for Kafka reconciliation, and checks exact forecast results. Use a fresh demo
workspace without additional documents. It does not erase existing data.

Browser regression tests exercise a dedicated, seeded demo stack at desktop and mobile
sizes, including sync, payment-delay scenarios, document validation, CSV errors, saved
planning balances, rescheduling, overdue indicators and cancellation. Run the smoke test
first to seed a fresh demo workspace. Browser tests retain their cancelled test document;
do not point them at your Nordea workspace or a demo containing plans you want to keep.

```sh
cd frontend
npx playwright install chromium
npm run test:e2e
# If the dedicated demo stack uses another port:
APP_URL=http://127.0.0.1:8081 npm run test:e2e
```

For statement browser tests, use a **separate disposable statement-mode test stack**;
the tests add synthetic documents/payments and must not run on business data:

```sh
# From repository root; the user statement workspace remains on 8081.
STATEMENT_APP_PORT=8082 docker compose -p sme-cashflow-statement-test \
  -f compose.yaml -f compose.statement.yaml up -d --wait
cd frontend
STATEMENT_E2E=1 APP_URL=http://127.0.0.1:8082 npm run test:e2e
```

Screenshots and failure traces are written to the ignored `frontend/test-results`.

For frontend development: run the backend on port 8080, then `npm run dev` in
`frontend` (Vite proxies `/api`). Sign in to the backend first. The standard
container-served UI is the recommended path for authentication and browser testing.

## Operations and troubleshooting

```sh
docker compose ps
docker compose logs --tail=100 app
docker compose logs --tail=100 kafka
curl -fsS http://localhost:8080/actuator/health/readiness
```

- **Pending events:** inspect Kafka and the app logs. Bank imports and documents remain
  durable during broker outages; processing resumes when Kafka returns.
- **Stale balance:** sync is manual in v0.1, to avoid uncontrolled sandbox requests.
  Data older than 15 minutes is marked stale. Re-sync before trusting a forecast.
- **Nordea HTTP 401/403:** check product subscription, token expiry and account access.
  Leave `NORDEA_PRIVATE_KEY_FILE` empty for the standard developer sandbox flow.
  An upstream failure is shown explicitly; never replaced by demo data.
- **Credential file permission denied:** the application runs as UID/GID 1000. Grant
  that user read access without making the files publicly readable.
- **Mode/account mismatch:** use a separate Compose project/database, preserving the old one.
- **Session token expired:** retry the action to fetch a new CSRF token, or reload and sign in.
- **Port in use:** set `APP_PORT` in `.env`.

## API

All `/api` endpoints require the owner login. Writes also require the CSRF token
and session cookie obtained from `GET /api/csrf`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/dashboard?days=30&incomeDelayDays=0` | Snapshot, documents, transactions and daily forecast |
| POST | `/api/documents` | Create invoice/expense |
| PUT | `/api/documents/{id}` | Edit using `{document: {...}, revision: n}` |
| POST | `/api/documents/{id}/cancel` | Cancel using `{revision: n}` |
| PUT | `/api/planning` | Save `{openingBalance: amount}`; `null` clears override |
| POST | `/api/recurring-expenses` | `{requestId, counterparty, amount, firstDueDate, endDate?}`; monthly expenses with idempotent request ID |
| POST | `/api/recurring-expenses/{id}/stop` | `{revision}`; stop generating, retain existing documents |
| POST | `/api/documents/import` | Multipart `file` CSV import |
| POST | `/api/statements/preview` | Statement-only read-only preview; multipart `file`, `accountId`, `accountName`, `closingBalance`, `closingDate` |
| POST | `/api/statements/import` | Same multipart fields plus preview `revision`; atomically import and update closing balance |
| POST | `/api/reconciliation/decisions` | `{accountId, transactionId, action, documentId?, revision, reason}`; action `LINK`, `UNLINK` or `RESUME` |
| GET | `/api/reconciliation/history?accountId=...&transactionId=...` | Manual correction history, newest first; URL-encode identifiers |
| POST | `/api/bank/sync` | Manual read-only sync (disabled in statement mode) |
| POST | `/api/demo/seed` | Load documents into an empty demo workspace only |
| GET | `/api/csrf` | Session-bound write token |

Document input accepts nullable `expectedDate` alongside `dueDate`. Dashboard documents
include `expectedDate`, `cancelled` and `revision`; top-level `planningBalance` is nullable.
Revision increments on edits/cancellation, not on reconciliation. Unknown document IDs
return HTTP 404. Schema migration V2 adds these fields without changing existing records,
balances or allocations. Back up the database before upgrading.
Migration V3 adds statement closing-date/import-revision metadata and permits
statement mode in the workspace constraint. Existing workspace modes, account facts,
documents and allocations are preserved; changing a populated workspace's mode remains forbidden.
The dashboard's `status.statementDate` is nullable outside confirmed statement imports.
Migration V4 adds persistent review decisions and append-only application audit history.
Existing allocations are preserved; payments without a decision default to automatic
matching. Dashboard transactions now include `matchMode` (`AUTO`, `MANUAL`, `EXCLUDED`),
`reviewRevision` and an `explanation` code. A decision needs the current payment revision;
`documentId` is required only for `LINK`, and must be omitted/null for `UNLINK`/`RESUME`.
Unknown payments/history queries return HTTP 404. Bank account balances are unchanged.
Migration V5 adds monthly rules, document occurrence provenance and `status.recurrenceError`.
It does not create rules or modify existing financial facts. Dashboard `recurringExpenses`
includes `nextMonth`, computed `nextDueDate`, stopped state and revision; documents include
nullable `recurringExpenseId`/`occurrenceMonth`. Forecasts include `expectedReceipts`,
positive `plannedOutgoings`, `closingBalance` and nullable `shortfallExplanation`.
The explanation contains date, negative-opening flag, before-day balance, same-day
receipts/outgoings/closing and dated document contributions through that day.

Validation errors return HTTP 400, conflicts 409 and bank integration failures 502,
using problem details. Browser/server errors never turn into a successful empty bank sync.
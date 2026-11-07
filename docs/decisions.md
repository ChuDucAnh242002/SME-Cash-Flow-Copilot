# Architecture decision records

These compact records describe **implemented prototype decisions**, not promises of
future features. Status: accepted for the current single-workspace prototype.
See [architecture](architecture.md) for the sequence/source map and
[reference](reference.md) for contracts.

## ADR-001: One deployable with a bank integration port

**Context.** The prototype needs several read-only sources and a demonstrable Kafka /
Kubernetes stack, but its workflows share financial invariants and one database.

**Decision.** Keep one Spring Boot application serving React. Separate logical services
for planning, statements, review and recurrence; adapt Nordea/demo snapshots through
`BankPort`. Statement uploads use their own preview/confirm service.

**Alternatives.** Microservices with separate data ownership; a completely
ports-and-adapters domain including a repository port.

**Consequences.** Atomic cross-workflow checks and same-origin authentication are
straightforward. Modules share `FinanceStore` and are not independently scalable or
enforced boundaries. One replica keeps in-memory sessions consistent. Extract services
only when a real ownership/scaling boundary justifies distributed consistency costs;
shared sessions/identity must precede multiple UI-serving replicas.

## ADR-002: Transactional outbox and idempotent reconciliation

**Context.** A successful financial write must not disappear because Kafka is down.
Publish acknowledgement and database commit cannot form one atomic local transaction.

**Decision.** Commit facts and outbox together. A scheduled publisher waits for broker
acknowledgement before marking published. The consumer commits its processed ID,
reconciliation and recovered diagnostics together. Events contain ID/type/version
only and reconcile current PostgreSQL state.

**Alternatives.** Send directly from the HTTP write path; reconcile everything only
synchronously; CDC-based outbox; Kafka transactions plus a separate projection store.

**Consequences.** Delivery is at least once and duplicates are expected. No event
sourcing or full CQRS is claimed. Query results can show temporary reconciliation lag,
so diagnostics disclose it; edits/reviews also reconcile synchronously for safe guards.
Unprocessable records retry rather than disappear, at the cost of a blocked partition
and operator intervention. A database restored behind Kafka offsets requeues unprocessed
local outbox IDs; this is not general lost-event repair.

## ADR-003: Deterministic, explainable plans separate from observations

**Context.** SMEs need to understand a shortage and test timing assumptions, without
mistaking sandbox fixtures or planned receipts for new observed money.

**Decision.** Compute daily EUR balances with `BigDecimal` from opening plus unpaid
active documents. Keep planning opening and expected dates separate from booked
balance and due dates. Import historical transactions for matching, not addition to
opening. Materialize monthly obligations as ordinary expense documents with stable
rule/month identities; reserve manual matches before automatic ones.

**Alternatives.** Learned cash-flow predictions, adding transactions to opening,
implicit recurrence inferred from history, expanding recurring rules only at query time.

**Consequences.** Exact contributors and assumptions are inspectable/testable; no AI,
tax, intraday, or customer-payment guarantee is implied. Missing obligations remain
missing. Individual monthly occurrences can be edited/cancelled without regeneration;
stopping preserves existing plans. Whole-payment review is simpler but does not support
splits, overpayments, refunds, or rule-wide editing/resume.

## ADR-004: Isolated demo and conservative Kubernetes recovery

**Context.** Users need a reproducible demonstration without bank credentials or
accidental mutation of the user's statement workspace. Kubernetes also needs evidence
of persistence/recovery, not just YAML that renders.

**Decision.** Provide a fixed, isolated Compose demo and synthetic Kubernetes overlay.
Kubernetes uses one app replica and single-instance PostgreSQL/Kafka StatefulSets,
default dynamic storage, namespace-relative Kafka DNS, and no public Ingress.
Database backup/restore requires explicit context and destructive confirmation,
validated private archives and a stopped-app safety backup, with attempted rollback
on restore/readiness failure.

**Alternatives.** Reset inside the financial UI; use one shared demo/business database;
deploy production HA operators, replicated storage and a managed backup service.

**Consequences.** Presentation/reset and recovery are demonstrable without changing
bank facts in another workspace. Fixed demo credentials are only for synthetic local
use. Node-local PVCs survive pod restarts, not disk/node loss. Kafka offsets are not
rolled back; idempotent republication repairs unprocessed outbox events after restore.
The procedure lacks off-site encryption/retention automation, HA and guaranteed cleanup
after a killed process. Production would require replacing these deployment assumptions.

# Optional Splunk and Jenkins integrations

These integrations are separate from normal startup. No existing demo, statement
database, Kubernetes namespace, or GitHub Actions workflow needs to be replaced.
Both operate on synthetic data only. Splunk does not participate in financial writes.

## Splunk: pipeline visibility

[The collector](../scripts/splunk-collector.mjs) polls the authenticated dashboard and
five authenticated Micrometer counters. It constructs an allowlisted
JSON event containing only:

- Unpublished, published-awaiting, and total pending event counts.
- Failed partition count, publication failure flag, and oldest pending timestamp.
- A `caught_up`, `catching_up`, or `retrying` state.
- Current per-process published/processed/duplicate/failure counters.

Account IDs, names, balances, documents, bank transactions, raw errors, and credentials
are **not exported**. The collector refuses non-demo mode or an unexpected account.
The executable permits only local or Compose service hostnames. Authentication
credentials are used to read operational endpoints, never placed in HEC events.

The loop waits ten seconds after each collection/delivery attempt; request latency
can increase the interval. Each sample is sent to the local HTTP Event Collector (HEC) with sourcetype
`cashflow:operations`, using its default `main` index. No raw application/framework
logs are forwarded: they may contain data that is not safe to export.
HTTP transport is deliberately limited to this local container lab; use trusted TLS,
restricted tokens, and a reviewed deployment before changing that boundary.

### Start the separate lab

Requires Node 22.12+, Docker Compose supporting `!override`/`!reset`, and additional
memory/disk for Splunk (the container is limited to 2 GiB). Review the
[Splunk terms](https://www.splunk.com/en_us/legal.html), image documentation and
licence limits first. This repository does **not** accept a licence for you.

Create a private `.env.splunk` in the repository root (`chmod 600 .env.splunk`).
Supply these values yourself; do not commit the file:

```text
SPLUNK_IMAGE=<reviewed-versioned-splunk/splunk-image>
SPLUNK_START_ARGS=<explicit-licence-acceptance-flags-required-by-that-image>
SPLUNK_GENERAL_TERMS=<explicit-terms-acceptance-required-by-that-image>
SPLUNK_PASSWORD=<local-admin-password>
SPLUNK_HEC_TOKEN=<random-local-token>
```

Use a versioned image, not `latest`. Review that version's acceptance requirements.
The official [container examples](https://splunk.github.io/docker-splunk/EXAMPLES.html)
and [runtime configuration](https://splunk.github.io/docker-splunk/ADVANCED.html)
describe startup/HEC provisioning. All variables are required; there is no automatic
acceptance default. Environment-based secrets are visible to Docker administrators.

Run **only with the fixed project and all three configuration files**:

```sh
docker compose --env-file .env.splunk -p sme-cashflow-observability \
  -f compose.yaml -f compose.demo.yaml -f compose.splunk.yaml up --build -d
APP_URL=http://127.0.0.1:8086 node scripts/smoke.mjs
```

App: **http://localhost:8086** (`developer / local-demo-password`).
Splunk Web: **http://localhost:8001** (`admin` and your local password).
HEC is not published on the host; the collector uses the private Compose network.
Startup can take several minutes. Check the services rather than assuming readiness:

```sh
docker compose --env-file .env.splunk -p sme-cashflow-observability \
  -f compose.yaml -f compose.demo.yaml -f compose.splunk.yaml ps
docker compose --env-file .env.splunk -p sme-cashflow-observability \
  -f compose.yaml -f compose.demo.yaml -f compose.splunk.yaml logs --tail=20 telemetry
```

Look for `telemetry_delivered`. `telemetry_failed` explicitly reports collection or
delivery failure and retries the next sample; there is no durable telemetry buffer,
so outages can leave sampling gaps. It is not an audit ledger or alerting service.
HEC HTTP 200 alone is not enough: the collector also checks application result code 0.

### Dashboard and recovery exercise

In Splunk Search, verify:

```spl
index=main sourcetype="cashflow:operations" earliest=-15m
| spath
| table _time state pending waitingToPublish awaitingReconciliation failedPartitions
```

Create a **Classic / Simple XML** dashboard and paste
[dashboard.xml](../deploy/splunk/dashboard.xml) into its source editor. It shows:

- Latest queue state and the sample age.
- Queue stages over time.
- Observed transitions into failure and recovery.
- Latest process counters, **not sums of repeated samples**.

Missing samples mean unknown visibility, not healthy processing. Counter decreases
can mean app restart. Failure observations are not the number of failed deliveries.
The dashboard uses `main`; adapt the queries if you intentionally configure another index.

For a real broker failure, pause **only this lab's Kafka service**, click **Sync bank
account** at port 8086, and wait for the dashboard's publication failure and a `retrying`
sample. Then unpause Kafka and verify pending counts drain and state becomes `caught_up`.
Always restore Kafka, including after a failed check:

```sh
docker compose --env-file .env.splunk -p sme-cashflow-observability \
  -f compose.yaml -f compose.demo.yaml -f compose.splunk.yaml pause kafka
# Sync using the lab UI; inspect the dashboard, then ALWAYS restore the broker:
docker compose --env-file .env.splunk -p sme-cashflow-observability \
  -f compose.yaml -f compose.demo.yaml -f compose.splunk.yaml unpause kafka
```

Do not use `demo.mjs check-recovery` here: it intentionally targets the normal port-8083
demo, not this lab. Stop the lab with the same Compose prefix followed by `down`;
data remains. Do not add `--volumes` unless you intend to delete this lab's data.

## Jenkins: one alternative verification pipeline

[Jenkinsfile](../Jenkinsfile) keeps GitHub Actions intact and reuses Maven, npm, smoke,
browser, and CLI commands rather than inventing a second test implementation.

Use a **trusted, dedicated Linux agent** labelled `cashflow-ci`, with one executor and:

- Java 21, Maven, Node 22.12+, Git.
- Docker Engine/Compose access and kubectl (rendering only; no cluster credentials needed).
- Playwright Linux system dependencies installed by the VM administrator before
  builds run. The pipeline downloads Chromium as the agent user without sudo.
- A free localhost port 8087 and sufficient disk/memory for Docker integration tests.

Docker access grants control of the agent host. Never run untrusted fork code on this
agent. Do not mount a Docker socket into a public Jenkins controller as a shortcut.
Use a Pipeline-from-SCM or trusted multibranch job with script path `Jenkinsfile`.
Required plugins: Pipeline/Declarative, Git, JUnit, Timestamper.
No controller is installed by this repository.

Stages:

1. Checkout and create a build-specific project/image identifier.
2. Run backend JUnit/Testcontainers, frontend unit/build, CLI guards, and Kustomize renders.
3. Build a dedicated synthetic Compose deployment on 8087; run exact HTTP smoke and
   browser regressions.
4. Publish JUnit results and browser failure artifacts; remove **only CI project**
   containers/volumes in `post`, including when verification fails.

[ci-compose.sh](../scripts/ci-compose.sh) requires a `cashflow-ci-<12-hex>-<build-number>`
project and a `build-<number>-<12-hex>` image tag. It pins all Compose files, skips local
dotenv configuration, and uses synthetic credentials without bank mounts.
One dedicated agent prevents fixed-port collisions across jobs. Do not run other
jobs/stacks on 8087. Images/build cache are retained; manage their retention on the
agent. A forcibly terminated controller/agent can interrupt cleanup; inspect the
build's CI project before removing anything.

To exercise the same deployment locally:

```sh
export CI_PROJECT=cashflow-ci-012345abcdef-1
export CI_IMAGE_TAG=build-1-012345abcdef
scripts/ci-compose.sh up --build -d --wait
APP_URL=http://127.0.0.1:8087 node scripts/smoke.mjs
scripts/ci-compose.sh down --volumes
```

## Validation boundaries

Collector tests cover allowlisting, invalid/foreign data, empty-queue consumer failure,
authenticated local HTTP collection, HEC payload shape, and explicit delivery errors.
CI Compose tests cover pinned configuration and rejected project identifiers.
Splunk Compose tests verify explicit licence configuration and local synthetic isolation.
Run `node --test scripts/*.test.mjs`.

Recorded local validation also includes a real broker pause: the live collector
observed `retrying` with an unpublished event, followed by `caught_up` with no backlog
after unpause, without changing the forecast. The isolated CI deployment passed
the exact financial smoke check and all 21 demo browser regressions.

These tests are **not a live Splunk search/ingestion certification**. Splunk must be
started after your licence review, and dashboard searches verified there. Likewise,
running the underlying commands is not proof that a Jenkins controller executed the
Declarative pipeline; validate it through your configured job before claiming that.

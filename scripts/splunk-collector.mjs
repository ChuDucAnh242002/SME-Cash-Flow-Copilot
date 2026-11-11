import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

export const metricNames = [
  "published",
  "publication.failures",
  "processed",
  "duplicates",
  "consumer.failures",
];

function count(value, field) {
  assert.ok(
    Number.isSafeInteger(value) && value >= 0,
    `Invalid operational count: ${field}`,
  );
  return value;
}

export function operationalSnapshot(dashboard) {
  assert.equal(dashboard.status.mode, "demo", "Telemetry requires demo mode.");
  assert.ok(
    dashboard.account === null || dashboard.account?.id === "DEMO-FI-EUR",
    "Telemetry requires the synthetic account.",
  );
  const { status } = dashboard;
  assert.ok(
    status.eventError === null || typeof status.eventError === "string",
    "Invalid publication error state.",
  );
  const { pipeline } = status;
  const waitingToPublish = count(pipeline.unpublishedEvents, "unpublished");
  const awaitingReconciliation = count(
    pipeline.awaitingReconciliation,
    "awaiting",
  );
  const failedPartitions = count(pipeline.failedPartitions, "failedPartitions");
  const pending = count(status.pendingEvents, "pending");
  assert.equal(pending, waitingToPublish + awaitingReconciliation);
  const oldestPendingAt = pipeline.oldestPendingAt;
  assert.ok(
    oldestPendingAt === null ||
      (typeof oldestPendingAt === "string" &&
        Number.isFinite(Date.parse(oldestPendingAt))),
    "Invalid oldest pending timestamp.",
  );
  return {
    kind: "pipeline_snapshot",
    pending,
    waitingToPublish,
    awaitingReconciliation,
    failedPartitions,
    publicationFailed: status.eventError !== null,
    oldestPendingAt:
      oldestPendingAt === null ? null : new Date(oldestPendingAt).toISOString(),
    state:
      failedPartitions > 0 || status.eventError !== null
        ? "retrying"
        : pending > 0
          ? "catching_up"
          : "caught_up",
  };
}

export async function collect(config, fetcher = fetch) {
  const headers = {
    authorization: `Basic ${Buffer.from(`${config.user}:${config.password}`).toString("base64")}`,
    connection: "close",
  };
  const read = async (path) => {
    const response = await fetcher(`${config.appUrl}${path}`, {
      headers,
      redirect: "error",
      signal: AbortSignal.timeout(10000),
    });
    assert.equal(response.status, 200, "Operational endpoint unavailable.");
    return response.json();
  };
  const event = operationalSnapshot(await read("/api/dashboard"));
  const counters = {};
  for (const name of metricNames) {
    const metric = await read(`/actuator/metrics/cashflow.events.${name}`);
    const value = metric.measurements.find(
      (entry) => entry.statistic === "COUNT",
    )?.value;
    assert.ok(Number.isFinite(value) && value >= 0, "Invalid process counter.");
    counters[name] = value;
  }
  return { ...event, counters };
}

export async function send(config, event, fetcher = fetch) {
  const response = await fetcher(config.hecUrl, {
    method: "POST",
    redirect: "error",
    headers: {
      authorization: `Splunk ${config.token}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      time: Date.now() / 1000,
      host: "cashflow-synthetic-lab",
      source: "cashflow-collector",
      sourcetype: "cashflow:operations",
      event,
    }),
    signal: AbortSignal.timeout(10000),
  });
  assert.equal(response.status, 200, "HEC endpoint unavailable.");
  const result = await response.json();
  assert.equal(result.code, 0, "HEC rejected the operational event.");
}

async function main() {
  const config = {
    appUrl: process.env.APP_URL,
    user: process.env.APP_USER,
    password: process.env.APP_PASSWORD,
    hecUrl: process.env.SPLUNK_HEC_URL,
    token: process.env.SPLUNK_HEC_TOKEN,
  };
  for (const value of Object.values(config)) {
    assert.ok(
      typeof value === "string" && value.length > 0,
      "Missing collector configuration.",
    );
  }
  const app = new URL(config.appUrl);
  const hec = new URL(config.hecUrl);
  // This integration deliberately cannot export to an external hostname.
  assert.ok(["app", "localhost", "127.0.0.1"].includes(app.hostname));
  assert.ok(["splunk", "localhost", "127.0.0.1"].includes(hec.hostname));
  assert.ok(["http:", "https:"].includes(app.protocol));
  assert.ok(["http:", "https:"].includes(hec.protocol));
  for (;;) {
    try {
      const event = await collect(config);
      await send(config, event);
      console.log(
        JSON.stringify({ kind: "telemetry_delivered", state: event.state }),
      );
    } catch {
      // Do not log raw HTTP bodies, financial data, credentials or exception messages.
      console.error(
        JSON.stringify({
          kind: "telemetry_failed",
          message:
            "Collection or local HEC delivery failed; retrying next sample.",
        }),
      );
    }
    await new Promise((resolve) => setTimeout(resolve, 10000));
  }
}

if (
  process.argv[1] &&
  pathToFileURL(process.argv[1]).href === import.meta.url
) {
  main().catch(() => {
    console.error("Collector configuration is invalid; no telemetry was sent.");
    process.exitCode = 1;
  });
}

import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";
import {
  collect,
  metricNames,
  operationalSnapshot,
  send,
} from "./splunk-collector.mjs";

const dashboard = {
  account: { id: "DEMO-FI-EUR", bookedBalance: 12500, name: "private name" },
  documents: [{ counterparty: "private customer" }],
  status: {
    mode: "demo",
    eventError: "private upstream error",
    pendingEvents: 3,
    pipeline: {
      unpublishedEvents: 2,
      awaitingReconciliation: 1,
      failedPartitions: 0,
      oldestPendingAt: "2026-11-07T10:00:00Z",
      consumerError: "private payload",
    },
  },
};

test("operational projection excludes financial facts and raw errors", () => {
  const result = operationalSnapshot(dashboard);
  assert.deepEqual(result, {
    kind: "pipeline_snapshot",
    pending: 3,
    waitingToPublish: 2,
    awaitingReconciliation: 1,
    failedPartitions: 0,
    publicationFailed: true,
    oldestPendingAt: "2026-11-07T10:00:00.000Z",
    state: "retrying",
  });
  assert.ok(!JSON.stringify(result).includes("private"));
  assert.ok(!JSON.stringify(result).includes("12500"));
});

test("foreign mode/account and invalid counts fail closed", () => {
  assert.throws(() =>
    operationalSnapshot({
      ...dashboard,
      status: { ...dashboard.status, mode: "nordea" },
    }),
  );
  assert.throws(() =>
    operationalSnapshot({ ...dashboard, account: { id: "business" } }),
  );
  assert.throws(() =>
    operationalSnapshot({
      ...dashboard,
      status: { ...dashboard.status, pendingEvents: 8 },
    }),
  );
  assert.throws(() =>
    operationalSnapshot({
      ...dashboard,
      status: {
        ...dashboard.status,
        pipeline: { ...dashboard.status.pipeline, failedPartitions: -1 },
      },
    }),
  );
});

test("consumer failure remains visible with an empty local queue", () => {
  const result = operationalSnapshot({
    ...dashboard,
    status: {
      ...dashboard.status,
      eventError: null,
      pendingEvents: 0,
      pipeline: {
        ...dashboard.status.pipeline,
        unpublishedEvents: 0,
        awaitingReconciliation: 0,
        failedPartitions: 1,
        oldestPendingAt: null,
      },
    },
  });
  assert.equal(result.state, "retrying");
  assert.equal(result.pending, 0);
  assert.equal(result.failedPartitions, 1);
});

test("collector delivers whitelisted JSON to a local HEC-compatible HTTP endpoint", async () => {
  let received;
  const server = createServer(async (request, response) => {
    response.setHeader("content-type", "application/json");
    if (request.url === "/api/dashboard") {
      assert.equal(
        request.headers.authorization,
        "Basic " + Buffer.from("developer:test-password").toString("base64"),
      );
      response.end(JSON.stringify(dashboard));
    } else if (request.url.startsWith("/actuator/metrics/")) {
      response.end(
        JSON.stringify({ measurements: [{ statistic: "COUNT", value: 4 }] }),
      );
    } else {
      assert.equal(request.headers.authorization, "Splunk local-test-token");
      let body = "";
      for await (const chunk of request) body += chunk;
      received = JSON.parse(body);
      response.end(JSON.stringify({ code: 0 }));
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const origin = `http://127.0.0.1:${server.address().port}`;
    const config = {
      appUrl: origin,
      user: "developer",
      password: "test-password",
      hecUrl: origin + "/hec",
      token: "local-test-token",
    };
    const event = await collect(config);
    await send(config, event);
    assert.equal(received.sourcetype, "cashflow:operations");
    assert.equal(received.event.pending, 3);
    assert.deepEqual(Object.keys(received.event.counters), metricNames);
    assert.ok(!JSON.stringify(received).includes("private"));
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("HTTP and HEC application errors are not treated as successful delivery", async () => {
  await assert.rejects(() => collect({}, async () => ({ status: 401 })));
  await assert.rejects(() =>
    send({}, {}, async () => ({
      status: 200,
      json: async () => ({ code: 6 }),
    })),
  );
});

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const origin = process.env.APP_URL ?? "http://127.0.0.1:8080";
const authorization = `Basic ${Buffer.from(
  `${process.env.APP_USER ?? "developer"}:${process.env.APP_PASSWORD ?? "local-demo-password"}`,
).toString("base64")}`;
let cookie = "";
let csrf;

async function call(path, method = "GET", body, expected = 200) {
  const headers = { Authorization: authorization };
  if (cookie) headers.Cookie = cookie;
  if (csrf && method !== "GET") headers[csrf.headerName] = csrf.token;
  if (body) headers["Content-Type"] = "application/json";
  const response = await fetch(origin + path, {
    method, headers, body: body ? JSON.stringify(body) : undefined,
  });
  const cookies = response.headers.getSetCookie();
  if (cookies.length) cookie = cookies.map(value => value.split(";")[0]).join("; ");
  const text = await response.text();
  assert.equal(response.status, expected, `${method} ${path}: ${text}`);
  return text ? JSON.parse(text) : undefined;
}

assert.equal((await fetch(origin + "/actuator/health/readiness")).status, 200);
assert.equal((await fetch(origin + "/api/dashboard")).status, 401);
csrf = await call("/api/csrf");
let data = await call("/api/dashboard");
assert.equal(data.status.mode, "demo", "Smoke tests require demo mode.");
if (data.documents.length === 0) await call("/api/demo/seed", "POST", undefined, 201);
await call("/api/bank/sync", "POST");
assert.equal((await call("/api/bank/sync", "POST")).imported, 0);

for (let retry = 0; retry < 60; retry++) {
  data = await call("/api/dashboard");
  if (data.status.pendingEvents === 0) break;
  await new Promise(resolve => setTimeout(resolve, 1000));
}
assert.equal(data.status.pendingEvents, 0, "Kafka reconciliation must finish.");
assert.equal(data.documents.length, 6, "Use a fresh demo workspace without extra documents.");
assert.equal(data.transactions.length, 3);
assert.equal(data.transactions.filter(tx => tx.documentId).length, 2);
assert.equal(data.account.bookedBalance, 12500);
assert.equal(data.forecast.minimumBalance, -2100);
assert.equal(data.forecast.series.length, 30);
assert.equal(data.forecast.series.at(-1).balance, 8800);
assert.ok(data.forecast.firstShortfall);

await call("/api/dashboard?days=999", "GET", undefined, 400);
await call("/api/documents", "POST", {
  kind: "INVOICE", counterparty: "Invalid", reference: "invalid-smoke",
  amount: -1, dueDate: "2026-10-29",
}, 400);
await call("/api/documents", "POST", {
  kind: "INVOICE", counterparty: "Duplicate", reference: "10 001",
  amount: 10, dueDate: "2026-10-29",
}, 409);
assert.equal((await call("/api/dashboard")).documents.length, 6);
console.log("PASS: auth, CSRF, persistence, duplicate sync, Kafka reconciliation, exact forecast and validation.");

if (process.argv.includes("--kafka-outage")) {
  const cwd = fileURLToPath(new URL("..", import.meta.url));
  const compose = (...args) => execFileSync("docker", ["compose", ...args], { cwd, stdio: "inherit" });
  compose("stop", "kafka");
  try {
    await call("/api/bank/sync", "POST");
    for (let retry = 0; retry < 45; retry++) {
      data = await call("/api/dashboard");
      if (data.status.eventError) break;
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    assert.ok(data.status.pendingEvents > 0, "An event must remain pending during a broker outage.");
    assert.ok(data.status.eventError, "Publication failure must be visible.");
    assert.equal(data.documents.length, 6);
    assert.equal(data.transactions.length, 3);
  } finally {
    compose("up", "-d", "--wait", "kafka");
  }
  for (let retry = 0; retry < 120; retry++) {
    data = await call("/api/dashboard");
    if (data.status.pendingEvents === 0) break;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.equal(data.status.pendingEvents, 0, "Pending events must recover after Kafka restarts.");
  assert.equal(data.status.eventError, null);
  assert.equal(data.forecast.minimumBalance, -2100);
  console.log("PASS: Kafka outage remains visible, financial data is durable, and reconciliation automatically recovers.");
}

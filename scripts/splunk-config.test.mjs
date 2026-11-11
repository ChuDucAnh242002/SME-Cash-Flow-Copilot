import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const cwd = fileURLToPath(new URL("..", import.meta.url));
const args = [
  "compose",
  "--env-file",
  "/dev/null",
  "-p",
  "sme-cashflow-observability",
  "-f",
  "compose.yaml",
  "-f",
  "compose.demo.yaml",
  "-f",
  "compose.splunk.yaml",
  "config",
  "--format",
  "json",
];
const env = {
  ...process.env,
  SPLUNK_IMAGE: "splunk/splunk:config-only",
  SPLUNK_START_ARGS: "not-accepted",
  SPLUNK_GENERAL_TERMS: "not-accepted",
  SPLUNK_PASSWORD: "config-test-password",
  SPLUNK_HEC_TOKEN: "config-test-token",
};

test("Splunk configuration requires explicit licence/terms variables", () => {
  for (const key of ["SPLUNK_START_ARGS", "SPLUNK_GENERAL_TERMS"]) {
    const result = spawnSync("docker", args, {
      cwd,
      env: { ...env, [key]: "" },
      encoding: "utf8",
    });
    assert.notEqual(result.status, 0);
    assert.ok(result.stderr.includes(key));
  }
});

test("Splunk lab is synthetic, local-only and never mounts bank secrets", () => {
  const config = JSON.parse(
    execFileSync("docker", args, { cwd, env, encoding: "utf8" }),
  );
  assert.equal(config.name, "sme-cashflow-observability");
  assert.equal(config.services.app.environment.BANK_MODE, "demo");
  assert.equal(config.services.app.image, "sme-cashflow-observability:local");
  assert.ok(!config.services.app.volumes?.length);
  assert.equal(config.services.app.ports[0].published, "8086");
  assert.deepEqual(
    config.services.splunk.ports.map((port) => [port.host_ip, port.target]),
    [["127.0.0.1", 8000]],
  );
  assert.equal(
    config.services.telemetry.environment.SPLUNK_HEC_URL,
    "http://splunk:8088/services/collector/event",
  );
  assert.equal(
    config.services.telemetry.environment.APP_URL,
    "http://app:8080",
  );
  assert.ok(config.services.telemetry.read_only);
  assert.ok(
    config.services.telemetry.volumes.every(
      (volume) => !volume.source.includes("secrets"),
    ),
  );
  assert.ok(
    Object.values(config.volumes).every((volume) =>
      volume.name.startsWith(config.name + "_"),
    ),
  );
});

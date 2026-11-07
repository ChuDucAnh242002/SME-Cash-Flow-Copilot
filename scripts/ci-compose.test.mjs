import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const script = fileURLToPath(new URL("./ci-compose.sh", import.meta.url));
const valid = {
  ...process.env,
  CI_PROJECT: "cashflow-ci-012345abcdef-1",
  CI_IMAGE_TAG: "build-1-012345abcdef",
};

test("CI wrapper rejects non-CI projects and invalid image tags before Docker", () => {
  for (const overrides of [
    { CI_PROJECT: "sme-cashflow-recruiter-demo" },
    { CI_PROJECT: "" },
    { CI_IMAGE_TAG: "local" },
  ]) {
    const result = spawnSync("bash", [script, "down", "--volumes"], {
      env: { ...valid, ...overrides },
      encoding: "utf8",
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /CI project|CI_PROJECT|image tag/);
  }
});

test("CI rendering pins synthetic isolation even with unrelated Compose overrides", () => {
  const config = JSON.parse(
    execFileSync("bash", [script, "config", "--format", "json"], {
      env: {
        ...valid,
        COMPOSE_FILE: "/nonexistent",
        COMPOSE_PROJECT_NAME: "existing-project",
        COMPOSE_PROFILES: "unrelated",
        APP_PASSWORD: "private-password",
        BANK_MODE: "nordea",
      },
      encoding: "utf8",
    }),
  );
  assert.equal(config.name, valid.CI_PROJECT);
  assert.equal(
    config.services.app.image,
    `sme-cashflow-ci:${valid.CI_IMAGE_TAG}`,
  );
  assert.equal(config.services.app.environment.BANK_MODE, "demo");
  assert.equal(
    config.services.app.environment.APP_PASSWORD,
    "local-demo-password",
  );
  assert.equal(config.services.app.environment.NORDEA_CLIENT_ID, "");
  assert.ok(!config.services.app.volumes?.length);
  assert.equal(config.services.app.ports[0].published, "8087");
  assert.ok(
    Object.values(config.volumes).every((volume) =>
      volume.name.startsWith(valid.CI_PROJECT + "_"),
    ),
  );
});

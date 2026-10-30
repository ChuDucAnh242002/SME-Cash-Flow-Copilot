import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { realpathSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const project = "sme-cashflow-recruiter-demo";
export const origin = "http://127.0.0.1:8083";
const root = realpathSync(fileURLToPath(new URL("..", import.meta.url)));
const files = [join(root, "compose.yaml"), join(root, "compose.demo.yaml")];
const authorization = `Basic ${Buffer.from("developer:local-demo-password").toString("base64")}`;

export function composeArgs(args) {
  return [
    "compose",
    "--env-file",
    "/dev/null",
    "--project-directory",
    root,
    "-p",
    project,
    "-f",
    files[0],
    "-f",
    files[1],
    ...args,
  ];
}

export function demoEnvironment(environment) {
  return Object.fromEntries(
    Object.entries(environment).filter(
      ([key]) =>
        !key.startsWith("COMPOSE_") &&
        !key.startsWith("NORDEA_") &&
        ![
          "APP_USER",
          "APP_PASSWORD",
          "APP_PORT",
          "POSTGRES_PASSWORD",
          "BANK_MODE",
        ].includes(key),
    ),
  );
}

const environment = demoEnvironment(process.env);
function docker(args, capture = false) {
  return execFileSync("docker", args, {
    cwd: root,
    env: environment,
    encoding: "utf8",
    stdio: capture ? ["ignore", "pipe", "pipe"] : "inherit",
  });
}
const compose = (args, capture = false) => docker(composeArgs(args), capture);

export function validateResources(containers, volumes) {
  for (const container of containers) {
    const labels = container.Config.Labels;
    assert.equal(labels["com.docker.compose.project"], project);
    assert.equal(
      labels["com.docker.compose.project.config_files"],
      files.join(","),
      "Refusing to touch containers created from another Compose configuration.",
    );
    if (labels["com.docker.compose.service"] === "app") {
      assert.ok(
        container.Config.Env.includes("BANK_MODE=demo"),
        "Refusing to touch a non-demo application.",
      );
    }
  }
  for (const volume of volumes) {
    assert.equal(
      volume.Labels?.["com.docker.compose.project"],
      project,
      "Refusing to touch volumes not owned by the dedicated demo project.",
    );
    assert.ok(
      ["postgres-data", "kafka-data"].includes(
        volume.Labels?.["com.docker.compose.volume"],
      ),
    );
    assert.equal(
      volume.Name,
      `${project}_${volume.Labels["com.docker.compose.volume"]}`,
    );
  }
}

function checkIsolation() {
  const ids = docker(
    ["ps", "-aq", "--filter", `label=com.docker.compose.project=${project}`],
    true,
  )
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  const containers = ids.length
    ? JSON.parse(docker(["inspect", ...ids], true))
    : [];
  const names = docker(["volume", "ls", "--format", "{{.Name}}"], true)
    .trim()
    .split(/\s+/)
    .filter((name) =>
      [`${project}_postgres-data`, `${project}_kafka-data`].includes(name),
    );
  const volumes = names.length
    ? JSON.parse(docker(["volume", "inspect", ...names], true))
    : [];
  validateResources(containers, volumes);
}

async function dashboard() {
  const response = await fetch(`${origin}/api/dashboard`, {
    headers: { authorization },
    signal: AbortSignal.timeout(10000),
  });
  assert.equal(
    response.status,
    200,
    "Demo dashboard is unavailable. Run the up command first.",
  );
  const data = await response.json();
  assert.equal(
    data.status.mode,
    "demo",
    "Refusing to operate on a non-demo workspace.",
  );
  assert.ok(
    !data.account || data.account.id === "DEMO-FI-EUR",
    "Refusing to operate on an unexpected account.",
  );
  return data;
}

function verify() {
  execFileSync(process.execPath, [join(root, "scripts/smoke.mjs")], {
    cwd: root,
    env: {
      ...environment,
      APP_URL: origin,
      APP_USER: "developer",
      APP_PASSWORD: "local-demo-password",
    },
    stdio: "inherit",
  });
}

export function parseCommand(args) {
  const [command, ...options] = args;
  assert.ok(
    ["up", "stop", "reset", "status", "verify"].includes(command),
    "Usage: node scripts/demo.mjs up [--build] | stop | status | verify | reset --confirm-reset [--build]",
  );
  const allowed =
    command === "up"
      ? ["--build"]
      : command === "reset"
        ? ["--confirm-reset", "--build"]
        : [];
  assert.ok(
    options.every((option) => allowed.includes(option)) &&
      new Set(options).size === options.length,
    "Unknown or repeated option.",
  );
  assert.ok(
    command !== "reset" || options.includes("--confirm-reset"),
    `Reset deletes only ${project}'s synthetic data. Repeat with reset --confirm-reset to confirm.`,
  );
  return { command, build: options.includes("--build") };
}

export async function main(args) {
  const { command, build } = parseCommand(args);
  checkIsolation();
  if (command === "stop") {
    compose(["down"]);
    console.log("Demo stopped. Data retained; run up to resume.");
    return;
  }
  if (command === "status") {
    compose(["ps"]);
    return;
  }
  if (command === "verify") {
    await dashboard();
    verify();
    return;
  }
  if (command === "reset") {
    console.log(
      `Resetting ONLY ${project}; statement and sandbox projects are not touched.`,
    );
    compose(["down", "--volumes"]);
  }
  compose(["up", ...(build ? ["--build"] : []), "-d", "--wait"]);
  const data = await dashboard();
  if (
    data.documents.length === 0 &&
    data.recurringExpenses.length === 0 &&
    data.planningBalance === null &&
    data.transactions.length === 0 &&
    data.account === null
  ) {
    verify();
  } else {
    console.log(
      "Existing demo session preserved. Use reset --confirm-reset for a fresh walkthrough.",
    );
  }
  console.log(
    `Demo ready: ${origin} (developer / local-demo-password). Synthetic data only.`,
  );
}

if (
  process.argv[1] &&
  pathToFileURL(realpathSync(process.argv[1])).href === import.meta.url
) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`Demo command failed: ${error.message}`);
    process.exitCode = 1;
  });
}

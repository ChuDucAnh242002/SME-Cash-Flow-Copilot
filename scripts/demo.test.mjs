import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { test } from "node:test";
import {
  composeArgs,
  demoEnvironment,
  parseCommand,
  project,
  validateResources,
} from "./demo.mjs";

test("demo commands pin project/configuration and never read the local dotenv file", () => {
  const args = composeArgs(["down", "--volumes"]);
  assert.equal(args[args.indexOf("-p") + 1], project);
  assert.equal(args[args.indexOf("--env-file") + 1], "/dev/null");
  assert.ok(args.some((value) => value.endsWith("/compose.demo.yaml")));
  assert.deepEqual(args.slice(-2), ["down", "--volumes"]);
});

test("demo environment drops Compose overrides and bank credentials but preserves Docker connectivity", () => {
  assert.deepEqual(
    demoEnvironment({
      PATH: "/bin",
      DOCKER_HOST: "unix:///var/run/docker.sock",
      COMPOSE_FILE: "foreign.yaml",
      COMPOSE_PROJECT_NAME: "statements",
      APP_PORT: "8081",
      APP_USER: "private",
      APP_PASSWORD: "private",
      POSTGRES_PASSWORD: "private",
      BANK_MODE: "nordea",
      NORDEA_CLIENT_ID: "private",
    }),
    { PATH: "/bin", DOCKER_HOST: "unix:///var/run/docker.sock" },
  );
});

test("rendered demo config has fixed local credentials, port, image and no bank mounts", () => {
  const config = JSON.parse(
    execFileSync("docker", composeArgs(["config", "--format", "json"]), {
      encoding: "utf8",
      env: demoEnvironment({
        ...process.env,
        BANK_MODE: "nordea",
        APP_PORT: "8081",
        POSTGRES_PASSWORD: "private",
      }),
    }),
  );
  assert.equal(config.name, project);
  assert.equal(config.services.app.environment.BANK_MODE, "demo");
  assert.equal(
    config.services.app.environment.APP_PASSWORD,
    "local-demo-password",
  );
  assert.equal(
    config.services.postgres.environment.POSTGRES_PASSWORD,
    "local-database-password",
  );
  assert.equal(config.services.app.image, "sme-cashflow-recruiter-demo:local");
  assert.equal(config.services.app.environment.NORDEA_CLIENT_SECRET_FILE, "");
  assert.equal(config.services.app.environment.NORDEA_ACCESS_TOKEN_FILE, "");
  assert.deepEqual(config.services.app.volumes ?? [], []);
  assert.equal(config.services.app.ports[0].host_ip, "127.0.0.1");
  assert.equal(config.services.app.ports[0].published, "8083");
  for (const [key, volume] of Object.entries(config.volumes)) {
    assert.equal(volume.name, `${project}_${key}`);
    assert.ok(!volume.external);
  }
});

test("reset requires explicit confirmation, rejects unknown arguments and never accepts a project override", () => {
  assert.throws(() => parseCommand(["reset"]), /confirm-reset/);
  assert.throws(() =>
    parseCommand(["reset", "--confirm-reset", "-p", "statements"]),
  );
  assert.throws(() => parseCommand(["up", "--volumes"]));
  assert.throws(() => parseCommand(["stop", "--confirm-reset"]));
  assert.throws(() => parseCommand(["up", "--build", "--build"]));
  assert.throws(() => parseCommand([]), /Usage/);
  assert.throws(() => parseCommand(["check-recovery"]), /confirm-outage/);
  assert.throws(() => parseCommand(["check-recovery", "--confirm-reset"]));
  assert.deepEqual(parseCommand(["check-recovery", "--confirm-outage"]), {
    command: "check-recovery",
    build: false,
  });
  assert.deepEqual(parseCommand(["reset", "--confirm-reset", "--build"]), {
    command: "reset",
    build: true,
  });
  for (const command of ["up", "stop", "status", "verify"]) {
    assert.deepEqual(parseCommand([command]), { command, build: false });
  }
});

test("unconfirmed reset exits before Docker is contacted", () => {
  const args = composeArgs([]);
  const root = args[args.indexOf("--project-directory") + 1];
  const result = spawnSync(process.execPath, ["scripts/demo.mjs", "reset"], {
    cwd: root,
    env: { PATH: "" },
    encoding: "utf8",
  });

  assert.equal(result.status, 1);
  assert.match(result.stderr, /confirm-reset/);
  assert.doesNotMatch(result.stderr, /ENOENT/);
});

test("unconfirmed recovery check exits before Docker is contacted", () => {
  const args = composeArgs([]);
  const result = spawnSync(
    process.execPath,
    ["scripts/demo.mjs", "check-recovery"],
    {
      cwd: args[args.indexOf("--project-directory") + 1],
      env: { PATH: "" },
      encoding: "utf8",
    },
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /confirm-outage/);
  assert.doesNotMatch(result.stderr, /ENOENT/);
});

test("existing demo resources must have the expected configuration and ownership before any operation", () => {
  const args = composeArgs([]);
  const files = args.filter((_, index) => args[index - 1] === "-f").join(",");
  const container = {
    Config: {
      Labels: {
        "com.docker.compose.project": project,
        "com.docker.compose.service": "app",
        "com.docker.compose.project.config_files": files,
      },
      Env: ["BANK_MODE=demo"],
    },
  };
  const volume = {
    Name: `${project}_postgres-data`,
    Labels: {
      "com.docker.compose.project": project,
      "com.docker.compose.volume": "postgres-data",
    },
  };
  assert.doesNotThrow(() => validateResources([container], [volume]));
  assert.throws(
    () =>
      validateResources(
        [{ Config: { ...container.Config, Env: ["BANK_MODE=statement"] } }],
        [],
      ),
    /non-demo/,
  );
  assert.throws(
    () =>
      validateResources(
        [
          {
            Config: {
              ...container.Config,
              Labels: {
                ...container.Config.Labels,
                "com.docker.compose.project.config_files": "foreign.yaml",
              },
            },
          },
        ],
        [],
      ),
    /another Compose/,
  );
  assert.throws(
    () => validateResources([], [{ ...volume, Labels: {} }]),
    /not owned/,
  );
  assert.throws(() =>
    validateResources([], [{ ...volume, Name: "statements-data" }]),
  );
});

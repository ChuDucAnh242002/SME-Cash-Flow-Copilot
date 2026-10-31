import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  chmodSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

test("restore requires an explicit confirmation before contacting Kubernetes", () => {
  const directory = mkdtempSync(path.join(tmpdir(), "cashflow-k8s-db-test-"));
  const marker = path.join(directory, "kubectl-called");
  const fakeKubectl = path.join(directory, "kubectl");
  writeFileSync(
    fakeKubectl,
    `#!/bin/sh\nprintf called > '${marker}'\nexit 99\n`,
    { mode: 0o700 },
  );
  chmodSync(fakeKubectl, 0o700);
  try {
    const result = spawnSync(
      "bash",
      [
        "scripts/k8s-db.sh",
        "restore",
        "/tmp/nonexistent-cashflow.dump",
        "--not-confirmed",
      ],
      {
        cwd: root,
        env: {
          ...process.env,
          PATH: `${directory}:${process.env.PATH}`,
          KUBE_CONTEXT: "test-context",
        },
        encoding: "utf8",
      },
    );
    assert.equal(result.status, 1);
    assert.match(result.stderr, /--confirm-restore/);
    assert.equal(result.stderr.includes("not found"), false);
    assert.equal(existsSync(marker), false);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

for (const scenario of [
  "successful restore",
  "restore failure",
  "backup failure",
]) {
  test(`${scenario} preserves the stop-backup-restore ordering and recovery contract`, () => {
    const directory = mkdtempSync(path.join(tmpdir(), "cashflow-k8s-db-test-"));
    const journal = path.join(directory, "journal.jsonl");
    const archive = path.join(directory, "original.dump");
    writeFileSync(archive, "requested backup");
    writeFileSync(
      `${archive}.sha256`,
      `${createHash("sha256").update("requested backup").digest("hex")}  original.dump\n`,
    );
    writeFileSync(
      path.join(directory, "kubectl"),
      `#!${process.execPath}
  const fs = require("node:fs");
  const args = process.argv.slice(2);
  fs.appendFileSync(process.env.FAKE_KUBE_JOURNAL, JSON.stringify(args) + "\\n");
  const command = args.at(-1);
  if (args.includes("get") && command.includes("jsonpath")) process.stdout.write("1");
  if (args.includes("exec") && command.includes("pg_dump")) {
    if (process.env.FAKE_SCENARIO === "backup failure") process.exit(1);
    process.stdout.write("pre-restore backup");
  }
  if (args.includes("exec") && command.includes("pg_restore")) {
    fs.readFileSync(0);
    if (!command.includes("--file=") && process.env.FAKE_SCENARIO === "restore failure") {
      const marker = process.env.FAKE_KUBE_JOURNAL + ".failed";
      if (!fs.existsSync(marker)) { fs.writeFileSync(marker, "failed"); process.exit(1); }
    }
  }
  `,
      { mode: 0o700 },
    );
    try {
      const result = spawnSync(
        "bash",
        [
          path.join(root, "scripts/k8s-db.sh"),
          "restore",
          archive,
          "--confirm-restore",
        ],
        {
          cwd: directory,
          env: {
            ...process.env,
            PATH: `${directory}:${process.env.PATH}`,
            KUBE_CONTEXT: "test-context",
            KUBE_NAMESPACE: "sme-cashflow",
            FAKE_KUBE_JOURNAL: journal,
            FAKE_SCENARIO: scenario,
          },
          encoding: "utf8",
        },
      );
      assert.equal(result.status, scenario === "successful restore" ? 0 : 1);
      const calls = readFileSync(journal, "utf8")
        .trim()
        .split("\n")
        .map((line) => JSON.parse(line));
      for (const call of calls) {
        assert.deepEqual(call.slice(0, 4), [
          "--context",
          "test-context",
          "--namespace",
          "sme-cashflow",
        ]);
      }
      const stopped = calls.findIndex((call) => call.includes("--replicas=0"));
      const dumped = calls.findIndex((call) => call.at(-1).includes("pg_dump"));
      assert.ok(stopped >= 0 && dumped > stopped);
      assert.ok(calls.at(-2).includes("--replicas=1"));
      if (scenario === "backup failure") {
        assert.ok(!calls.some((call) => call.at(-1).includes("dropdb")));
        assert.match(
          result.stderr,
          /database was not replaced and app was restarted/,
        );
      } else {
        const restored = calls.filter(
          (call) =>
            call.includes("exec") &&
            call.at(-1).includes("pg_restore --no-owner"),
        );
        assert.equal(restored.length, scenario === "restore failure" ? 2 : 1);
        assert.match(
          scenario === "restore failure" ? result.stderr : result.stdout,
          scenario === "restore failure"
            ? /pre-restore database was restored and the app is running/
            : /Restore completed/,
        );
        const backupLine = result.stdout
          .split("\n")
          .find((line) => line.startsWith("Backup created:"));
        const safety = backupLine.slice(
          "Backup created: ".length,
          -" (mode 600)".length,
        );
        assert.equal(statSync(safety).mode & 0o777, 0o600);
        assert.equal(readFileSync(safety, "utf8"), "pre-restore backup");
        assert.ok(existsSync(`${safety}.sha256`));
      }
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

for (const [label, args, environment, setup, expected] of [
  [
    "explicit context",
    ["backup", "test.dump"],
    { KUBE_CONTEXT: "" },
    () => {},
    /set KUBE_CONTEXT explicitly/,
  ],
  [
    "valid namespace",
    ["backup", "test.dump"],
    { KUBE_NAMESPACE: "foreign/namespace" },
    () => {},
    /invalid KUBE_NAMESPACE/,
  ],
  [
    "no backup overwrite",
    ["backup", "test.dump"],
    {},
    (directory) => writeFileSync(path.join(directory, "test.dump"), "existing"),
    /refusing to overwrite/,
  ],
  [
    "correct checksum",
    ["restore", "test.dump", "--confirm-restore"],
    {},
    (directory) => {
      writeFileSync(path.join(directory, "test.dump"), "changed");
      writeFileSync(
        path.join(directory, "test.dump.sha256"),
        `${"0".repeat(64)}  test.dump\n`,
      );
    },
    /archive checksum mismatch/,
  ],
  [
    "checksum names the requested archive",
    ["verify", "test.dump"],
    {},
    (directory) => {
      writeFileSync(path.join(directory, "test.dump"), "content");
      const digest = createHash("sha256").update("content").digest("hex");
      writeFileSync(
        path.join(directory, "test.dump.sha256"),
        `${digest}  another.dump\n`,
      );
    },
    /must identify this archive only/,
  ],
]) {
  test(`${label} is required before Kubernetes is contacted`, () => {
    const directory = mkdtempSync(path.join(tmpdir(), "cashflow-k8s-db-test-"));
    const marker = path.join(directory, "kubectl-called");
    writeFileSync(
      path.join(directory, "kubectl"),
      `#!/bin/sh\nprintf called > '${marker}'\nexit 99\n`,
      { mode: 0o700 },
    );
    try {
      setup(directory);
      const result = spawnSync(
        "bash",
        [path.join(root, "scripts/k8s-db.sh"), ...args],
        {
          cwd: directory,
          env: {
            ...process.env,
            PATH: `${directory}:${process.env.PATH}`,
            KUBE_CONTEXT: "test-context",
            KUBE_NAMESPACE: "sme-cashflow",
            ...environment,
          },
          encoding: "utf8",
        },
      );
      assert.equal(result.status, 1);
      assert.match(result.stderr, expected);
      assert.equal(existsSync(marker), false);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

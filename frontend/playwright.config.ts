import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  testMatch:
    process.env.STATEMENT_E2E === "1"
      ? "**/statements.spec.ts"
      : ["**/dashboard.spec.ts", "**/planning.spec.ts"],
  use: {
    baseURL: process.env.APP_URL ?? "http://127.0.0.1:8080",
    httpCredentials: {
      username: process.env.APP_USER ?? "developer",
      password: process.env.APP_PASSWORD ?? "local-demo-password",
    },
    trace: "retain-on-failure",
  },
});

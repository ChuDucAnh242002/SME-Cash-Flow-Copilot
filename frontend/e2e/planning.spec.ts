import { test, expect, type APIRequestContext } from "@playwright/test";

test.beforeEach(async ({ request }) => {
  const response = await request.get("/api/dashboard");
  expect(response.ok()).toBe(true);
  const dashboard = await response.json();
  expect(
    dashboard.status.mode,
    "Planning tests require a dedicated, seeded demo workspace; never target Nordea.",
  ).toBe("demo");
  expect(
    dashboard.documents.some(
      (document: { reference: string; cancelled: boolean }) =>
        document.reference === "20003" && !document.cancelled,
    ),
    "Load the demo scenario before running browser tests.",
  ).toBe(true);
});

async function write(
  api: APIRequestContext,
  path: string,
  method: "PUT" | "POST",
  data: unknown,
) {
  const csrfResponse = await api.get("/api/csrf");
  expect(csrfResponse.ok()).toBe(true);
  const csrf = await csrfResponse.json();
  const response = await api.fetch(path, {
    method,
    data,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok(), await response.text()).toBe(true);
}

test("saved planning balance is separate from bank balance, survives reload and can be reset", async ({
  page,
  request,
}) => {
  try {
    await page.goto("/");
    await page.getByLabel("Planning opening balance / EUR").fill("12000");
    await page.getByRole("button", { name: "Save planning balance" }).click();
    await expect(
      page.getByRole("heading", { name: /Scenario opening balance/ }),
    ).toContainText("12,000");
    await expect(page.locator(".metric").first()).toContainText("12,500");
    await expect(page.locator(".metric.risk strong")).toContainText("2,600");
    await page.reload();
    await expect(
      page.getByRole("heading", { name: /Scenario opening balance/ }),
    ).toBeVisible();
    await expect(page.getByLabel("Planning opening balance / EUR")).toHaveValue(
      "12000",
    );
    await page.getByRole("button", { name: "Use bank balance" }).click();
    await expect(
      page.getByRole("heading", {
        name: "Plan with a realistic opening balance",
      }),
    ).toBeVisible();
    await expect(page.locator(".metric.risk strong")).toContainText("2,100");
  } finally {
    await write(request, "/api/planning", "PUT", { openingBalance: null });
  }
});

test("rescheduling an expense removes the shortfall without changing the contractual due date", async ({
  page,
  request,
}) => {
  const before = await (await request.get("/api/dashboard")).json();
  const equipment = before.documents.find(
    (doc: { reference: string }) => doc.reference === "20003",
  );
  const newDate = new Date(`${equipment.dueDate}T12:00:00Z`);
  newDate.setUTCDate(newDate.getUTCDate() + 14);
  try {
    await page.goto("/");
    await page.getByRole("button", { name: "Edit 20003", exact: true }).click();
    await page
      .getByLabel("Expected payment date")
      .fill(newDate.toISOString().slice(0, 10));
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(
      page.getByRole("heading", { name: "Room to breathe." }),
    ).toBeVisible();
    const updated = await (await request.get("/api/dashboard")).json();
    expect(updated.forecast.minimumBalance).toBe(2700);
    expect(
      updated.documents.find((doc: { id: string }) => doc.id === equipment.id)
        .dueDate,
    ).toBe(equipment.dueDate);
    await page.reload();
    await page.getByRole("button", { name: "Edit 20003", exact: true }).click();
    await expect(page.getByLabel("Expected payment date")).toHaveValue(
      newDate.toISOString().slice(0, 10),
    );
  } finally {
    const current = await (await request.get("/api/dashboard")).json();
    const document = current.documents.find(
      (doc: { id: string }) => doc.id === equipment.id,
    );
    await write(request, `/api/documents/${equipment.id}`, "PUT", {
      revision: document.revision,
      document: {
        kind: equipment.kind,
        counterparty: equipment.counterparty,
        reference: equipment.reference,
        amount: equipment.amount,
        dueDate: equipment.dueDate,
        expectedDate: equipment.expectedDate,
      },
    });
  }
});

test("overdue documents can be cancelled explicitly and stay excluded after reload", async ({
  page,
  request,
}) => {
  const reference = `E2E-${Date.now()}`;
  let created: { id: string; revision: number; cancelled: boolean } | undefined;
  try {
    await page.goto("/");
    await page
      .getByRole("button", { name: "+ Add invoice or expense" })
      .click();
    await page
      .getByRole("combobox", { name: "Type", exact: true })
      .selectOption("EXPENSE");
    await page
      .getByLabel("Customer or supplier")
      .fill("Cancellation test supplier");
    await page.getByLabel("Payment reference", { exact: true }).fill(reference);
    await page.getByLabel("Amount / EUR").fill("100");
    await page.getByLabel("Due date", { exact: true }).fill("2020-01-01");
    await page
      .getByRole("button", { name: "Save document", exact: true })
      .click();
    const row = page.getByRole("row").filter({
      has: page.getByRole("cell", { name: reference, exact: true }),
    });
    await expect(row).toContainText("Overdue");
    await page
      .getByRole("button", { name: `Cancel ${reference}`, exact: true })
      .click();
    await expect(
      page.getByRole("region", { name: "Confirm document cancellation" }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "Confirm cancellation", exact: true })
      .click();
    await expect(row).toContainText("Cancelled");
    await expect(row.getByRole("button")).toHaveCount(0);
    await page.reload();
    await expect(row).toContainText("Cancelled");
    const after = await (await request.get("/api/dashboard")).json();
    created = after.documents.find(
      (doc: { reference: string }) => doc.reference === reference,
    );
    expect(after.forecast.minimumBalance).toBe(-2100);
    expect(after.transactions).toHaveLength(3);
  } finally {
    if (!created) {
      const current = await (await request.get("/api/dashboard")).json();
      created = current.documents.find(
        (doc: { reference: string }) => doc.reference === reference,
      );
    }
    if (created && !created.cancelled)
      await write(request, `/api/documents/${created.id}/cancel`, "POST", {
        revision: created.revision,
      });
  }
});

test("matched payment references and cancellation controls are protected", async ({
  page,
}) => {
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Cancel 10001", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Edit 10001", exact: true }).click();
  await expect(
    page.getByLabel("Payment reference", { exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("combobox", { name: "Type", exact: true }),
  ).toBeDisabled();
  await expect(page.getByLabel("Expected payment date")).toBeEnabled();
});

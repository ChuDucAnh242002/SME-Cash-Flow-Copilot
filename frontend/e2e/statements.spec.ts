import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";

const header = "transactionId,bookingDate,amount,reference,counterparty\n";
const rows =
  "e2e-receipt,2026-10-28,400,STMT-INV,Customer\n" +
  "e2e-rent,2026-10-28,-200,STMT-RENT,Landlord\n" +
  "e2e-software,2026-10-28,-89,,Software\n";
const today = new Date().toLocaleDateString("en-CA", {
  timeZone: "Europe/Helsinki",
});

async function write(api: APIRequestContext, path: string, data: unknown) {
  const csrfResponse = await api.get("/api/csrf");
  expect(csrfResponse.ok()).toBe(true);
  const csrf = await csrfResponse.json();
  const response = await api.post(path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok(), await response.text()).toBe(true);
}

async function fill(page: Page, content = rows) {
  const accountId = page.getByLabel("Statement account ID", { exact: true });
  if (await accountId.isEditable()) await accountId.fill("E2E-STATEMENT-EUR");
  else await expect(accountId).toHaveValue("E2E-STATEMENT-EUR");
  await page
    .getByLabel("Statement account name", { exact: true })
    .fill("Statement Test Oy");
  await page.getByLabel("Statement closing balance / EUR").fill("5000");
  await page.getByLabel("Statement closing date", { exact: true }).fill(today);
  await page.getByLabel("Statement CSV", { exact: true }).setInputFiles({
    name: "statement.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(header + content),
  });
}

test.beforeEach(async ({ request }) => {
  const response = await request.get("/api/dashboard");
  expect(response.ok()).toBe(true);
  const dashboard = await response.json();
  expect(
    dashboard.status.mode,
    "Use a dedicated statement-mode test workspace.",
  ).toBe("statement");
  expect(
    dashboard.account?.id ?? "E2E-STATEMENT-EUR",
    "Never run against business statement data.",
  ).toBe("E2E-STATEMENT-EUR");
  expect(dashboard.planningBalance).toBeNull();
});

test("preview writes nothing; confirmation reconciles partial payments without adding transactions to the closing balance", async ({
  page,
  request,
}) => {
  const initial = await (await request.get("/api/dashboard")).json();
  const due = new Date(`${today}T12:00:00Z`);
  due.setUTCDate(due.getUTCDate() + 5);
  for (const [kind, counterparty, reference, amount] of [
    ["INVOICE", "Customer", "STMT-INV", 1000],
    ["EXPENSE", "Landlord", "STMT-RENT", 200],
  ]) {
    if (
      !initial.documents.some(
        (doc: { reference: string }) => doc.reference === reference,
      )
    ) {
      await write(request, "/api/documents", {
        kind,
        counterparty,
        reference,
        amount,
        dueDate: due.toISOString().slice(0, 10),
      });
    }
  }
  await page.goto("/");
  await expect(
    page.getByText("UPLOADED STATEMENTS", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Sync bank account" }),
  ).toHaveCount(0);
  await fill(page);
  await page
    .getByRole("button", { name: "Preview statement", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Review before importing" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Confirm statement import" }),
  ).toBeDisabled();
  const untouched = await (await request.get("/api/dashboard")).json();
  expect(untouched.account).toEqual(initial.account);
  expect(
    untouched.transactions.map((tx: { externalId: string }) => tx.externalId),
  ).toEqual(
    initial.transactions.map((tx: { externalId: string }) => tx.externalId),
  );
  await page
    .getByRole("checkbox", { name: /I confirm this closing balance/ })
    .check();
  await page.getByRole("button", { name: "Confirm statement import" }).click();
  await expect(
    page.getByText(/Imported \d+ new transactions; skipped \d+ duplicates/),
  ).toBeVisible();
  await expect
    .poll(
      async () =>
        (await (await request.get("/api/dashboard")).json()).status
          .pendingEvents,
    )
    .toBe(0);
  await page.reload();
  await expect(
    page.getByText("Statement closing balance", { exact: true }),
  ).toBeVisible();
  await expect(page.locator(".metric").first()).toContainText("5,000");
  const after = await (await request.get("/api/dashboard")).json();
  expect(after.transactions).toHaveLength(3);
  expect(
    after.documents.find(
      (doc: { reference: string }) => doc.reference === "STMT-INV",
    ).paidAmount,
  ).toBe(400);
  expect(after.forecast.openingBalance).toBe(5000);
  expect(after.forecast.series.at(-1).balance).toBe(5600);
  await expect(
    page.getByRole("row").filter({
      has: page.getByRole("cell", { name: "STMT-INV", exact: true }),
    }),
  ).toContainText("Partially paid");
  await page.getByRole("tab", { name: /Statement transactions/ }).click();
  await expect(
    page.getByRole("cell", { name: "Matched", exact: true }),
  ).toHaveCount(2);
  await expect(
    page.getByRole("cell", { name: "Needs review", exact: true }),
  ).toHaveCount(1);
  await page.screenshot({
    path: "test-results/statements-desktop.png",
    fullPage: true,
  });
});

test("duplicate preview is explicit, input changes invalidate it and changed transactions fail atomically", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await fill(page);
  await page
    .getByRole("button", { name: "Preview statement", exact: true })
    .click();
  await expect(
    page.getByText("0 new transactions", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "Duplicate / skip", exact: true }),
  ).toHaveCount(3);
  await page
    .getByLabel("Statement account name", { exact: true })
    .fill("Changed name");
  await expect(
    page.getByRole("heading", { name: "Review before importing" }),
  ).toHaveCount(0);
  await fill(page);
  await page
    .getByRole("button", { name: "Preview statement", exact: true })
    .click();
  await page
    .getByRole("checkbox", { name: /I confirm this closing balance/ })
    .check();
  await page.getByRole("button", { name: "Confirm statement import" }).click();
  await expect(
    page.getByText(/Imported 0 new transactions; skipped 3 duplicates/),
  ).toBeVisible();
  await fill(page, "new,2026-10-28,10,,New\n" + rows.replace(",400,", ",401,"));
  await page
    .getByRole("button", { name: "Preview statement", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "same ID has different details",
  );
  const after = await (await request.get("/api/dashboard")).json();
  expect(after.transactions).toHaveLength(3);
  expect(after.account.bookedBalance).toBe(5000);
  await fill(page, "invalid,not-a-date,10,,Company");
  await page
    .getByRole("button", { name: "Preview statement", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("ISO date");
});

test("statement form and preview fit mobile without page overflow", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await fill(page);
  await page
    .getByRole("button", { name: "Preview statement", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Review before importing" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  await page.screenshot({
    path: "test-results/statements-mobile.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Discard preview" }).click();
  await expect(
    page.getByRole("heading", { name: "Review before importing" }),
  ).toHaveCount(0);
});

import {
  test,
  expect,
  type APIRequestContext,
  type Page,
} from "@playwright/test";

test.beforeEach(async ({ request }) => {
  const response = await request.get("/api/dashboard");
  expect(response.ok()).toBe(true);
  const dashboard = await response.json();
  expect(dashboard.status.mode, "Use a dedicated seeded demo stack.").toBe(
    "demo",
  );
  expect(dashboard.account?.id).toBe("DEMO-FI-EUR");
});

async function write(api: APIRequestContext, path: string, data?: unknown) {
  const csrfResponse = await api.get("/api/csrf");
  expect(csrfResponse.ok()).toBe(true);
  const csrf = await csrfResponse.json();
  const response = await api.post(path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok(), await response.text()).toBe(true);
}

async function open(page: Page, id: string) {
  await page.getByRole("tab", { name: /Bank transactions/ }).click();
  await page
    .getByRole("button", { name: `Review payment ${id}`, exact: true })
    .click();
  await expect(
    page.getByRole("region", { name: "Payment review", exact: true }),
  ).toBeVisible();
}

async function correct(page: Page, reason: string) {
  await page.getByLabel("Reason for correction", { exact: true }).fill(reason);
  await page
    .getByRole("button", { name: "Review correction", exact: true })
    .click();
  await expect(
    page.getByRole("region", { name: "Confirm payment correction" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Confirm correction", exact: true })
    .click();
  await expect(
    page.getByText("Payment correction saved.", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Payment review", exact: true }),
  ).toHaveCount(0);
}

test("manual partial match updates forecast, records audit and survives bank sync", async ({
  page,
  request,
}) => {
  const before = await (await request.get("/api/dashboard")).json();
  const reference = `REVIEW-${Date.now()}`;
  await write(request, "/api/documents", {
    kind: "EXPENSE",
    counterparty: "Review software supplier",
    reference,
    amount: 100,
    dueDate: new Date().toLocaleDateString("en-CA", {
      timeZone: "Europe/Helsinki",
    }),
  });
  const document = (
    await (await request.get("/api/dashboard")).json()
  ).documents.find((doc: { reference: string }) => doc.reference === reference);
  try {
    await page.goto("/");
    await open(page, "demo-unmatched-1");
    const panel = page.getByRole("region", {
      name: "Payment review",
      exact: true,
    });
    await expect(panel).toContainText("Missing payment reference");
    await page
      .getByRole("combobox", { name: "Document to link", exact: true })
      .selectOption(document.id);
    await expect(
      page.getByRole("button", { name: "Review correction", exact: true }),
    ).toBeDisabled();
    await correct(page, "Verified software charge against supplier invoice");
    const matched = await (await request.get("/api/dashboard")).json();
    expect(matched.account).toEqual(before.account);
    expect(
      matched.documents.find((doc: { id: string }) => doc.id === document.id)
        .paidAmount,
    ).toBe(89);
    expect(matched.forecast.series.at(-1).balance).toBe(
      before.forecast.series.at(-1).balance - 11,
    );
    await page
      .getByRole("button", { name: "Sync bank account", exact: true })
      .click();
    await expect(
      page.getByText("Bank sync complete.", { exact: false }),
    ).toBeVisible();
    await page.reload();
    await open(page, "demo-unmatched-1");
    await expect(panel).toContainText("Manually linked / protected");
    await expect(panel.locator(".review-history")).toContainText(
      "Verified software charge against supplier invoice",
    );
    await expect(panel.locator(".review-history")).toContainText("developer");
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    await page.screenshot({
      path: "test-results/payment-review-mobile.png",
      fullPage: true,
    });
    await correct(page, "Undo test match; retain unmatched decision");
    await page.reload();
    await open(page, "demo-unmatched-1");
    await expect(panel).toContainText("Held unmatched by your decision");
    await page
      .getByRole("combobox", { name: "Review action", exact: true })
      .selectOption("RESUME");
    await correct(page, "Return test payment to automatic matching");
  } finally {
    const csrfResponse = await request.get("/api/csrf");
    const csrf = await csrfResponse.json();
    const current = await (await request.get("/api/dashboard")).json();
    const payment = current.transactions.find(
      (tx: { externalId: string }) => tx.externalId === "demo-unmatched-1",
    );
    if (payment.documentId === document.id) {
      await write(request, "/api/reconciliation/decisions", {
        accountId: payment.accountId,
        transactionId: payment.externalId,
        action: "UNLINK",
        revision: payment.reviewRevision,
        reason: "Test cleanup",
      });
    }
    const latest = (
      await (await request.get("/api/dashboard")).json()
    ).documents.find((doc: { id: string }) => doc.id === document.id);
    const response = await request.post(
      `/api/documents/${document.id}/cancel`,
      {
        data: { revision: latest.revision },
        headers: { [csrf.headerName]: csrf.token },
      },
    );
    expect(response.ok(), await response.text()).toBe(true);
  }
});

test("removing an automatic match holds it through sync, then explicit resume restores it", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await open(page, "demo-receipt-1");
  await correct(page, "Check automatic receipt before approving");
  try {
    await page
      .getByRole("button", { name: "Sync bank account", exact: true })
      .click();
    await expect(
      page.getByText("Bank sync complete.", { exact: false }),
    ).toBeVisible();
    await page.reload();
    await open(page, "demo-receipt-1");
    const panel = page.getByRole("region", {
      name: "Payment review",
      exact: true,
    });
    await expect(panel).toContainText("Held unmatched by your decision");
    const current = await (await request.get("/api/dashboard")).json();
    expect(
      current.documents.find(
        (doc: { reference: string }) => doc.reference === "10001",
      ).paidAmount,
    ).toBe(0);
    await page
      .getByRole("combobox", { name: "Review action", exact: true })
      .selectOption("RESUME");
    await correct(page, "Confirmed original receipt reference");
    await open(page, "demo-receipt-1");
    await expect(panel).toContainText("Matched by payment reference");
    await expect(panel.locator(".review-history")).toContainText(
      "Confirmed original receipt reference",
    );
    await page.screenshot({
      path: "test-results/payment-review-desktop.png",
      fullPage: true,
    });
  } finally {
    const current = await (await request.get("/api/dashboard")).json();
    const payment = current.transactions.find(
      (tx: { externalId: string }) => tx.externalId === "demo-receipt-1",
    );
    if (payment.matchMode === "EXCLUDED")
      await write(request, "/api/reconciliation/decisions", {
        accountId: payment.accountId,
        transactionId: payment.externalId,
        action: "RESUME",
        revision: payment.reviewRevision,
        reason: "Test cleanup",
      });
  }
});

test("stale review cannot overwrite another session's correction", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await open(page, "demo-rent-1");
  const before = await (await request.get("/api/dashboard")).json();
  const payment = before.transactions.find(
    (tx: { externalId: string }) => tx.externalId === "demo-rent-1",
  );
  await write(request, "/api/reconciliation/decisions", {
    accountId: payment.accountId,
    transactionId: payment.externalId,
    action: "UNLINK",
    revision: payment.reviewRevision,
    reason: "Concurrent review session",
  });
  try {
    const panel = page.getByRole("region", {
      name: "Payment review",
      exact: true,
    });
    await expect(panel.getByRole("alert")).toContainText("match changed", {
      timeout: 10000,
    });
    await expect(
      panel.getByRole("button", { name: "Review correction", exact: true }),
    ).toBeDisabled();
    const unchanged = await (await request.get("/api/dashboard")).json();
    expect(
      unchanged.transactions.find(
        (tx: { externalId: string }) => tx.externalId === payment.externalId,
      ).matchMode,
    ).toBe("EXCLUDED");
  } finally {
    const current = await (await request.get("/api/dashboard")).json();
    const latest = current.transactions.find(
      (tx: { externalId: string }) => tx.externalId === payment.externalId,
    );
    if (latest.matchMode === "EXCLUDED")
      await write(request, "/api/reconciliation/decisions", {
        accountId: latest.accountId,
        transactionId: latest.externalId,
        action: "RESUME",
        revision: latest.reviewRevision,
        reason: "Test cleanup",
      });
  }
});

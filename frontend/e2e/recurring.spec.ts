import { test, expect, type APIRequestContext } from "@playwright/test";

async function write(
  api: APIRequestContext,
  path: string,
  method: "POST" | "PUT",
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

test.beforeEach(async ({ request }) => {
  const data = await (await request.get("/api/dashboard")).json();
  expect(
    data.status.mode,
    "Use a dedicated seeded demo workspace, not business data.",
  ).toBe("demo");
  expect(data.account?.id).toBe("DEMO-FI-EUR");
  expect(data.planningBalance).toBeNull();
});

test("cash breakdown explains first shortage and opens the contributing document for date adjustment", async ({
  page,
  request,
}) => {
  const before = await (await request.get("/api/dashboard")).json();
  const equipment = before.documents.find(
    (document: { reference: string }) => document.reference === "20003",
  );
  try {
    await page.goto("/");
    const region = page.getByRole("region", {
      name: "Forecast explanation",
      exact: true,
    });
    await expect(
      region.getByRole("heading", {
        name: "30-day cash breakdown",
        exact: true,
      }),
    ).toBeVisible();
    await expect(region.locator(".cash-breakdown")).toContainText("12,500");
    await expect(region.locator(".cash-breakdown")).toContainText("10,900");
    await expect(region.locator(".cash-breakdown")).toContainText("14,600");
    await expect(region.locator(".cash-breakdown")).toContainText("8,800");
    await expect(
      region.getByRole("heading", { name: /Why cash first falls below zero/ }),
    ).toBeVisible();
    await region
      .getByText("Documents through the first shortage (2)", { exact: true })
      .click();
    await expect(
      region.getByRole("cell", { name: /Team salaries/ }),
    ).toBeVisible();
    await expect(
      region.getByRole("cell", { name: /Equipment supplier/ }),
    ).toBeVisible();
    await region
      .getByRole("button", { name: "Adjust forecast 20003", exact: true })
      .click();
    const form = page.getByRole("form", { name: "Edit document", exact: true });
    await expect(form).toBeVisible();
    const moved = new Date(`${equipment.dueDate}T12:00:00Z`);
    moved.setUTCDate(moved.getUTCDate() + 14);
    await form
      .getByLabel("Expected payment date")
      .fill(moved.toISOString().slice(0, 10));
    await form
      .getByRole("button", { name: "Save changes", exact: true })
      .click();
    await expect(region).toContainText(
      "No negative opening or daily closing balance",
    );
    const after = await (await request.get("/api/dashboard")).json();
    expect(after.forecast.minimumBalance).toBe(2700);
    expect(after.forecast.closingBalance).toBe(8800);
    expect(
      after.documents.find(
        (document: { id: string }) => document.id === equipment.id,
      ).dueDate,
    ).toBe(equipment.dueDate);
    await page.screenshot({
      path: "test-results/forecast-explanation-desktop.png",
      fullPage: true,
    });
  } finally {
    const current = await (await request.get("/api/dashboard")).json();
    const document = current.documents.find(
      (value: { id: string }) => value.id === equipment.id,
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

test("monthly rules create unique ordinary expenses, retry safely and stop without deleting existing plans", async ({
  page,
  request,
}) => {
  const description = `Monthly E2E ${Date.now()}`;
  let ruleId: string | undefined;
  try {
    await page.goto("/");
    await page.getByText(/Monthly recurring expenses \(\d+ rules\)/).click();
    await page
      .getByRole("button", { name: "Add monthly expense", exact: true })
      .click();
    await expect(
      page.getByLabel("Recurring supplier or description", { exact: true }),
    ).toBeFocused();
    await page
      .getByLabel("Recurring supplier or description", { exact: true })
      .fill(description);
    await page.getByLabel("Monthly amount / EUR", { exact: true }).fill("100");
    const submitted = page.waitForRequest(
      (request) =>
        request.url().endsWith("/api/recurring-expenses") &&
        request.method() === "POST",
    );
    await page
      .getByRole("button", { name: "Save monthly rule", exact: true })
      .click();
    const input = (await submitted).postDataJSON();
    ruleId = input.requestId;
    await expect(
      page.getByText("Monthly rule saved.", { exact: false }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Add monthly expense", exact: true }),
    ).toBeFocused();
    const created = await (await request.get("/api/dashboard?days=90")).json();
    const occurrences = created.documents.filter(
      (document: { recurringExpenseId: string }) =>
        document.recurringExpenseId === ruleId,
    );
    expect(occurrences.length).toBeGreaterThanOrEqual(3);
    expect(occurrences.length).toBeLessThanOrEqual(4);
    expect(
      new Set(
        occurrences.map(
          (document: { reference: string }) => document.reference,
        ),
      ).size,
    ).toBe(occurrences.length);
    await write(request, "/api/recurring-expenses", "POST", input);
    const repeated = await (await request.get("/api/dashboard")).json();
    expect(
      repeated.recurringExpenses.filter(
        (rule: { id: string }) => rule.id === ruleId,
      ),
    ).toHaveLength(1);
    expect(
      repeated.documents.filter(
        (document: { recurringExpenseId: string }) =>
          document.recurringExpenseId === ruleId,
      ),
    ).toHaveLength(occurrences.length);
    await page.reload();
    const first = occurrences[0];
    await page
      .getByRole("button", { name: `Edit ${first.reference}`, exact: true })
      .click();
    await expect(
      page
        .getByRole("form", { name: "Edit document" })
        .getByRole("combobox", { name: "Type", exact: true }),
    ).toBeDisabled();
    await page.getByRole("button", { name: "Close form", exact: true }).click();
    await page.getByText(/Monthly recurring expenses \(\d+ rules\)/).click();
    await page
      .getByRole("button", { name: `Stop monthly ${description}`, exact: true })
      .click();
    await expect(
      page.getByRole("region", { name: "Confirm recurring rule stop" }),
    ).toContainText("All existing documents");
    await expect(
      page
        .getByRole("region", { name: "Confirm recurring rule stop" })
        .locator("p")
        .first(),
    ).toBeFocused();
    await page
      .getByRole("button", { name: "Confirm stop rule", exact: true })
      .click();
    await expect(
      page.getByText("Monthly rule stopped.", { exact: false }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Add monthly expense", exact: true }),
    ).toBeFocused();
    const stopped = await (await request.get("/api/dashboard?days=90")).json();
    expect(
      stopped.recurringExpenses.find(
        (rule: { id: string }) => rule.id === ruleId,
      ).stopped,
    ).toBe(true);
    expect(
      stopped.documents.filter(
        (document: { recurringExpenseId: string }) =>
          document.recurringExpenseId === ruleId,
      ),
    ).toEqual(occurrences);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    await page.screenshot({
      path: "test-results/recurring-expenses-mobile.png",
      fullPage: true,
    });
  } finally {
    const current = await (await request.get("/api/dashboard")).json();
    const rule = current.recurringExpenses.find(
      (value: { id: string }) => value.id === ruleId,
    );
    if (rule && !rule.stopped)
      await write(request, `/api/recurring-expenses/${rule.id}/stop`, "POST", {
        revision: rule.revision,
      });
    for (const document of current.documents.filter(
      (value: { recurringExpenseId: string; cancelled: boolean }) =>
        value.recurringExpenseId === ruleId && !value.cancelled,
    )) {
      await write(request, `/api/documents/${document.id}/cancel`, "POST", {
        revision: document.revision,
      });
    }
  }
});

test("negative opening is labelled separately from expenses causing a later shortage", async ({
  page,
  request,
}) => {
  try {
    await write(request, "/api/planning", "PUT", { openingBalance: -1 });
    await page.goto("/");
    const region = page.getByRole("region", {
      name: "Forecast explanation",
      exact: true,
    });
    await expect(
      region.getByRole("heading", {
        name: "The opening balance is already negative",
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", {
        name: "Opening balance below zero.",
        exact: true,
      }),
    ).toBeVisible();
    await expect(region).toContainText("did not cause the negative opening");
    await expect(
      region.getByRole("heading", {
        name: /Why cash first falls below zero/,
      }),
    ).toHaveCount(0);
  } finally {
    await write(request, "/api/planning", "PUT", { openingBalance: null });
  }
});

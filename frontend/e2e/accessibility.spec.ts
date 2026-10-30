import { test, expect } from "@playwright/test";

test("skip link and activity tabs work from the keyboard", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("tab", { name: /Documents/ })).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to main content" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();

  const documents = page.getByRole("tab", { name: /Documents/ });
  const transactions = page.getByRole("tab", { name: /Bank transactions/ });
  await documents.focus();
  await page.keyboard.press("ArrowRight");
  await expect(transactions).toBeFocused();
  await expect(transactions).toHaveAttribute("aria-selected", "true");
  await expect(documents).toHaveAttribute("tabindex", "-1");
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Import documents CSV")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("tabpanel")).toBeFocused();
  await transactions.focus();
  await page.keyboard.press("ArrowRight");
  await expect(documents).toBeFocused();
  await page.keyboard.press("End");
  await expect(transactions).toBeFocused();
  await page.keyboard.press("Home");
  await expect(documents).toBeFocused();
  await page.keyboard.press("ArrowLeft");
  await expect(transactions).toBeFocused();
});

test("forms and review confirmations move focus and return it without losing draft input", async ({
  page,
}) => {
  await page.goto("/");
  const edit = page.getByRole("button", { name: "Edit 20003", exact: true });
  await edit.click();
  const form = page.getByRole("form", { name: "Edit document", exact: true });
  await expect(
    form.getByRole("combobox", { name: "Type", exact: true }),
  ).toBeFocused();
  const expected = form.getByLabel("Expected payment date", { exact: true });
  await expect(expected).toHaveAccessibleDescription(
    "Optional. Used in the forecast; leaves the due date unchanged.",
  );
  const supplier = form.getByLabel("Customer or supplier", { exact: true });
  await supplier.fill("Unsaved keyboard draft");
  await page.waitForResponse((response) =>
    response.url().includes("/api/dashboard?"),
  );
  await expect(supplier).toHaveValue("Unsaved keyboard draft");
  await expect(supplier).toBeFocused();
  await page.getByRole("button", { name: "Close form", exact: true }).click();
  await expect(edit).toBeFocused();

  const cancel = page.getByRole("button", {
    name: "Cancel 20003",
    exact: true,
  });
  await cancel.click();
  const confirmation = page.getByRole("region", {
    name: "Confirm document cancellation",
  });
  await expect(confirmation.locator("p")).toBeFocused();
  await confirmation
    .getByRole("button", { name: "Keep document", exact: true })
    .click();
  await expect(cancel).toBeFocused();

  await page.getByRole("tab", { name: /Bank transactions/ }).click();
  const review = page.getByRole("button", {
    name: "Review payment demo-rent-1",
    exact: true,
  });
  await review.click();
  const panel = page.getByRole("region", {
    name: "Payment review",
    exact: true,
  });
  await expect(
    panel.getByRole("heading", {
      name: "Review payment demo-rent-1",
      exact: true,
    }),
  ).toBeFocused();
  await panel
    .getByLabel("Reason for correction")
    .fill("Keyboard preview only, do not save");
  await panel
    .getByRole("button", { name: "Review correction", exact: true })
    .click();
  const correction = page.getByRole("region", {
    name: "Confirm payment correction",
  });
  await expect(correction.locator("p").first()).toBeFocused();
  await correction.getByRole("button", { name: "Back to review" }).click();
  await expect(
    panel.getByRole("button", { name: "Review correction", exact: true }),
  ).toBeFocused();
  await panel.getByRole("button", { name: "Close review" }).click();
  await expect(review).toBeFocused();
});

test("failed saves focus the error and retain the entered document", async ({
  page,
}) => {
  let retry = false;
  await page.route("**/api/documents", async (route) => {
    if (route.request().method() === "POST") {
      if (retry) await route.fulfill({ status: 204 });
      else
        await route.fulfill({
          status: 409,
          json: { detail: "Accessibility test: reference already used." },
        });
    } else await route.continue();
  });
  await page.goto("/");
  await page
    .getByRole("button", { name: "+ Add invoice or expense", exact: true })
    .click();
  const form = page.getByRole("form", { name: "New document", exact: true });
  await form.getByLabel("Customer or supplier").fill("Unsaved supplier");
  await form
    .getByLabel("Payment reference", { exact: true })
    .fill("KEYBOARD-ONLY");
  await form.getByLabel("Amount / EUR").fill("10");
  await form
    .getByRole("button", { name: "Save document", exact: true })
    .click();
  const error = page
    .getByRole("alert")
    .filter({ hasText: "Accessibility test:" });
  await expect(error).toBeFocused();
  await expect(
    form.getByLabel("Payment reference", { exact: true }),
  ).toHaveValue("KEYBOARD-ONLY");
  await expect(
    form.getByRole("button", { name: "Save document", exact: true }),
  ).toBeEnabled();
  retry = true;
  await form
    .getByRole("button", { name: "Save document", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "+ Add invoice or expense", exact: true }),
  ).toBeFocused();
  await expect(page.getByRole("status")).toContainText("Document saved.");
});

test("scenario changes label the previous forecast until the new query finishes", async ({
  page,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/dashboard?**", async (route) => {
    if (new URL(route.request().url()).searchParams.get("days") === "90") {
      await gate;
    }
    await route.continue();
  });
  await page.goto("/");
  const summary = page.getByRole("region", {
    name: "Financial summary",
    exact: true,
  });
  await expect(summary).toContainText("30-day outlook");
  await page
    .getByRole("combobox", { name: "Horizon", exact: true })
    .selectOption("90");
  await expect(summary).toHaveAttribute("aria-busy", "true");
  await expect(summary).toContainText("30-day outlook");
  await expect(
    page.getByRole("status").filter({ hasText: "Updating scenario..." }),
  ).toBeVisible();
  release();
  await expect(summary).toHaveAttribute("aria-busy", "false");
  await expect(summary).toContainText("90-day outlook");
  await expect(
    page.getByRole("heading", { name: "90-day cash breakdown", exact: true }),
  ).toBeVisible();
});

test("loading, stale-dashboard retry and action progress are explicit", async ({
  page,
}) => {
  let releaseLoad!: () => void;
  const loadGate = new Promise<void>((resolve) => {
    releaseLoad = resolve;
  });
  let fail = false;
  await page.route("**/api/dashboard?**", async (route) => {
    await loadGate;
    if (fail)
      await route.fulfill({
        status: 503,
        json: { detail: "Accessibility test: refresh unavailable." },
      });
    else await route.continue();
  });
  await page.goto("/");
  await expect(
    page.getByRole("status").filter({ hasText: "Loading your workspace..." }),
  ).toBeVisible();
  releaseLoad();
  await expect(
    page.getByRole("heading", { name: "Cash-flow forecast", exact: true }),
  ).toBeVisible();
  fail = true;
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "Showing the last successful dashboard." }),
  ).toBeVisible({ timeout: 10000 });
  fail = false;
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveCount(0);

  let releaseSync!: () => void;
  const syncGate = new Promise<void>((resolve) => {
    releaseSync = resolve;
  });
  await page.route("**/api/bank/sync", async (route) => {
    await syncGate;
    await route.fulfill({ json: { imported: 0, observed: 3 } });
  });
  const sync = page.getByRole("button", {
    name: "Sync bank account",
    exact: true,
  });
  await sync.click();
  await expect(page.getByRole("status")).toContainText(
    "Working... Please wait.",
  );
  await expect(sync).toBeDisabled();
  releaseSync();
  await expect(page.getByRole("status")).toContainText("Bank sync complete.");
  await expect(sync).toBeEnabled();
});

test("narrow viewport preserves keyboard focus and readable metadata", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await page.goto("/");
  const add = page.getByRole("button", {
    name: "+ Add invoice or expense",
    exact: true,
  });
  await add.click();
  await expect(
    page.getByRole("form", { name: "New document" }).getByRole("combobox"),
  ).toBeFocused();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  const field = page.getByLabel("Customer or supplier", { exact: true });
  await field.focus();
  const styles = await field.evaluate((element) => ({
    outline: getComputedStyle(element).outlineStyle,
    outlineWidth: getComputedStyle(element).outlineWidth,
    height: element.getBoundingClientRect().height,
  }));
  expect(styles.outline).toBe("solid");
  expect(parseFloat(styles.outlineWidth)).toBeGreaterThanOrEqual(3);
  expect(styles.height).toBeGreaterThanOrEqual(44);
  await page.getByRole("button", { name: "Close form", exact: true }).click();
  await page.getByRole("tab", { name: /Documents/ }).focus();
  await page.keyboard.press("ArrowRight");
  await expect(
    page.getByRole("tab", { name: /Bank transactions/ }),
  ).toBeFocused();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  await page.screenshot({
    path: "test-results/accessibility-mobile.png",
    fullPage: true,
  });
});

test("muted financial labels and table metadata meet normal-text contrast", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByRole("tab", { name: /Documents/ })).toBeVisible();
  const samples = await page.evaluate(() => {
    function luminance(rgb: number[]) {
      const linear = rgb.map((value) => {
        const channel = value / 255;
        return channel <= 0.04045
          ? channel / 12.92
          : ((channel + 0.055) / 1.055) ** 2.4;
      });
      return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
    }
    return [
      ".subtitle",
      ".eyebrow",
      ".source-strip",
      ".metric small",
      ".cash-breakdown dt",
      "th",
      "td small",
      ".tabs button:not(.selected)",
      ".axis-label",
      ".table-note",
    ].flatMap((selector) =>
      Array.from(document.querySelectorAll(selector))
        .slice(0, 3)
        .map((element) => {
          const style = getComputedStyle(element);
          const foreground = (
            element instanceof SVGElement ? style.fill : style.color
          )
            .match(/[\d.]+/g)!
            .map(Number);
          let parent: Element | null = element;
          let background = [255, 255, 255];
          while (parent) {
            const color = getComputedStyle(parent)
              .backgroundColor.match(/[\d.]+/g)!
              .map(Number);
            if (color.length === 3 || color[3] === 1) {
              background = color;
              break;
            }
            parent = parent.parentElement;
          }
          const a = luminance(foreground),
            b = luminance(background);
          return {
            selector,
            ratio: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05),
          };
        }),
    );
  });
  expect(samples.length).toBeGreaterThan(10);
  for (const sample of samples)
    expect(sample.ratio, sample.selector).toBeGreaterThanOrEqual(4.5);
});

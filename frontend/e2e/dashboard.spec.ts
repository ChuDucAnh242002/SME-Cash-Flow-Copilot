import { test, expect } from "@playwright/test";

test("shows the real demo forecast, scenario controls and matched transactions", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Your cash, in perspective." })).toBeVisible();
  await expect(page.getByText("DEMO DATA", { exact: true })).toBeVisible();
  await expect(page.locator(".metric.risk strong")).toContainText("2,100");
  await page.getByLabel("Customer payment delay").selectOption("14");
  await expect(page.getByText("Receipts are assumed on their due date plus 14 days;", { exact: false })).toBeAttached();
  await page.getByRole("tab", { name: /Bank transactions/ }).click();
  await expect(page.getByRole("cell", { name: "Matched", exact: true })).toHaveCount(2);
  await expect(page.getByRole("cell", { name: "Needs review", exact: true })).toHaveCount(1);
  await page.getByRole("button", { name: "Sync bank account" }).click();
  await expect(page.getByText("Bank sync complete.", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sync bank account" })).toBeEnabled();
  await expect(page.locator(".message.error")).toHaveCount(0);
  expect(errors).toEqual([]);
  await page.screenshot({ path: "test-results/dashboard-desktop.png", fullPage: true });
});

test("form and CSV errors are visible without creating duplicate documents", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "+ Add invoice or expense" }).click();
  await page.getByLabel("Customer or supplier").fill("Duplicate company");
  await page.getByLabel("Payment reference", { exact: true }).fill("10001");
  await page.getByLabel("Amount / EUR").fill("10");
  await page.getByRole("button", { name: "Save document" }).click();
  await expect(page.getByRole("alert")).toContainText("reference is already used");
  await page.getByLabel("Import documents CSV").setInputFiles({
    name: "invalid.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("kind,counterparty,reference,amount,dueDate\nINVOICE,Company,1,-1,2026-10-29"),
  });
  await expect(page.getByRole("alert")).toContainText("CSV row 1");
  await expect(page.getByRole("tabpanel").getByRole("row")).toHaveCount(7);
});

test("mobile layout fits and still exposes the forecast and forms", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(page.locator(".metric.risk strong")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Cash-flow forecast" })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(overflow).toBe(false);
  await page.getByRole("button", { name: "+ Add invoice or expense" }).click();
  await expect(page.getByLabel("Payment reference", { exact: true })).toBeVisible();
  await page.screenshot({ path: "test-results/dashboard-mobile.png", fullPage: true });
});

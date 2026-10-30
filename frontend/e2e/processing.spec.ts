import { test, expect } from "@playwright/test";

test("processing diagnostics distinguish consumer failure, publication backlog and recovery", async ({
  page,
}) => {
  let state: "consumer" | "publisher" | "recovered" = "consumer";
  await page.route("**/api/dashboard?**", async (route) => {
    const response = await route.fetch();
    const data = await response.json();
    data.status.pendingEvents = state === "publisher" ? 3 : 0;
    data.status.eventError =
      state === "publisher"
        ? "Kafka publication is unavailable. Event retained; reconciliation will retry."
        : null;
    data.status.pipeline = {
      unpublishedEvents: state === "publisher" ? 2 : 0,
      awaitingReconciliation: state === "publisher" ? 1 : 0,
      oldestPendingAt: state === "publisher" ? "2026-10-30T10:00:00Z" : null,
      failedPartitions: state === "consumer" ? 1 : 0,
      consumerError:
        state === "consumer"
          ? "Payment reconciliation failed. The affected Kafka partition is retained for retry."
          : null,
    };
    await route.fulfill({ json: data });
  });
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText(
    "Payment reconciliation failed.",
  );
  await page
    .getByRole("link", { name: "Processing diagnostics", exact: true })
    .click();
  await page.locator("#processing summary").click();
  const panel = page.locator("#processing");
  await expect(panel).toContainText("Retrying a failure");
  await expect(panel).toContainText("no automatic discard");
  await expect(
    page.getByRole("heading", { name: "Cash-flow forecast", exact: true }),
  ).toBeVisible();
  state = "publisher";
  await page.reload();
  await page.locator("#processing summary").click();
  await expect(panel).toContainText("Catching up");
  await expect(
    panel.getByText("Waiting to publish", { exact: true }).locator(".."),
  ).toContainText("2");
  await expect(
    panel
      .getByText("Published, awaiting reconciliation", { exact: true })
      .locator(".."),
  ).toContainText("1");
  await expect(page.getByRole("alert")).toContainText(
    "Kafka publication is unavailable",
  );
  state = "recovered";
  await page.reload();
  await page.locator("#processing summary").click();
  await expect(panel).toContainText("Caught up");
  await expect(panel).toContainText("does not prove");
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.setViewportSize({ width: 320, height: 800 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
});

import type { Dashboard } from "./types";

export function ProcessingPanel({ status }: { status: Dashboard["status"] }) {
  const { pipeline } = status;
  const waiting = status.pendingEvents > 0;
  const blocked = pipeline.failedPartitions > 0;
  return (
    <details className="panel processing-panel" id="processing">
      <summary>
        Processing diagnostics /{" "}
        {blocked ? "Retrying a failure" : waiting ? "Catching up" : "Caught up"}
      </summary>
      <div className="processing-content">
        <dl className="cash-breakdown">
          <div>
            <dt>Waiting to publish</dt>
            <dd>{pipeline.unpublishedEvents}</dd>
          </div>
          <div>
            <dt>Published, awaiting reconciliation</dt>
            <dd>{pipeline.awaitingReconciliation}</dd>
          </div>
          <div>
            <dt>Failed Kafka partitions</dt>
            <dd>{pipeline.failedPartitions}</dd>
          </div>
        </dl>
        <p>
          Oldest pending event:{" "}
          {pipeline.oldestPendingAt
            ? `${new Date(pipeline.oldestPendingAt).toLocaleString("en-GB", { timeZone: "Europe/Helsinki" })} Helsinki`
            : "None"}
          .
        </p>
        <p>
          Last successful reconciliation:{" "}
          {status.lastReconciled
            ? `${new Date(status.lastReconciled).toLocaleString("en-GB", { timeZone: "Europe/Helsinki" })} Helsinki`
            : "Not yet run"}
          .
        </p>
        <p>
          {blocked
            ? "A consumer record is failing. Its partition is retried without skipping the record. An operator must resolve persistent schema or database errors; there is no automatic discard or dashboard skip button."
            : waiting
              ? "Financial changes are saved. Payment matching is catching up; unpaid forecast amounts may change after reconciliation."
              : "No known reconciliation backlog. This does not prove that the bank snapshot is fresh or that Kafka is reachable while idle."}
        </p>
        <p className="form-note">
          Queue counts and failure records survive restarts. Retries are
          automatic. Readiness checks the app and database, not Kafka; a broker
          outage does not erase saved plans. Do not repeatedly import or reset
          to clear a backlog. Check server logs using topic, partition and
          offset.
        </p>
      </div>
    </details>
  );
}

#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

namespace=${KUBE_NAMESPACE:-sme-cashflow}
context=${KUBE_CONTEXT:-}
pod=postgres-0

usage() {
  cat <<'EOF'
Usage:
  KUBE_CONTEXT=<context> scripts/k8s-db.sh backup <archive.dump>
  KUBE_CONTEXT=<context> scripts/k8s-db.sh verify <archive.dump>
  KUBE_CONTEXT=<context> scripts/k8s-db.sh restore <archive.dump> --confirm-restore

KUBE_NAMESPACE defaults to sme-cashflow. Backups are PostgreSQL custom-format
archives with a SHA-256 sidecar. Restore stops the app, creates a protected pre-restore
backup, restores the database, requeues unreconciled outbox events,
and restarts the app. Kafka is a replayable transport, not part of this backup.
EOF
}

fail() {
  printf 'Kubernetes database operation failed: %s\n' "$*" >&2
  exit 1
}

[[ "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || fail "invalid KUBE_NAMESPACE."
[[ -n "$context" ]] || fail "set KUBE_CONTEXT explicitly; no current context is assumed."
command -v kubectl >/dev/null || fail "kubectl is required."
command -v sha256sum >/dev/null || fail "sha256sum is required."

kube() {
  kubectl --context "$context" --namespace "$namespace" --request-timeout=300s "$@"
}

require_database() {
  kube get statefulset/postgres deployment/copilot >/dev/null || return
  kube wait --for=condition=Ready "pod/$pod" --timeout=180s >/dev/null
}

resolve_archive() {
  local input=$1
  [[ "$input" != -* ]] || fail "archive paths must not start with '-'."
  local base
  base=$(basename -- "$input")
  [[ "$base" =~ ^[A-Za-z0-9._-]+\.dump$ ]] || fail "archive filename must end in .dump and use only letters, digits, dot, underscore or hyphen."
  local directory
  directory=$(cd -- "$(dirname -- "$input")" && pwd -P) || fail "archive directory does not exist."
  printf '%s/%s\n' "$directory" "$base"
}

verify_local_archive() {
  local archive=$1
  local base
  base=$(basename -- "$archive")
  [[ -f "$archive" && ! -L "$archive" && -s "$archive" ]] || fail "archive must be a non-empty regular file."
  [[ -f "$archive.sha256" && ! -L "$archive.sha256" ]] || fail "missing regular checksum sidecar: $archive.sha256"
  local expected actual
  expected=$(cat -- "$archive.sha256")
  [[ "$expected" =~ ^[a-f0-9]{64} && "$expected" == "${expected:0:64}  $base" ]] || fail "checksum sidecar must identify this archive only."
  actual=$(sha256sum -- "$archive")
  [[ "${actual%% *}  $base" == "$expected" ]] || fail "archive checksum mismatch."
}

verify_cluster_archive() {
  local archive=$1
  require_database || fail "PostgreSQL workload is unavailable."
  kube exec -i "$pod" -- sh -ec 'pg_restore --file=/dev/null' < "$archive" || fail "PostgreSQL rejected the archive."
}

backup_archive() {
  local archive=$1
  local base directory temporary checksum_temporary digest
  archive=$(resolve_archive "$archive")
  base=$(basename -- "$archive")
  directory=$(dirname -- "$archive")
  [[ ! -e "$archive" && ! -L "$archive" && ! -e "$archive.sha256" && ! -L "$archive.sha256" ]] || fail "refusing to overwrite an existing backup or checksum."
  require_database || fail "PostgreSQL workload is unavailable."
  temporary=$(mktemp "$directory/.${base}.tmp.XXXXXXXX") || fail "cannot create a private temporary backup."
  checksum_temporary=$(mktemp "$directory/.${base}.sha256.tmp.XXXXXXXX") || {
    rm -f -- "$temporary"
    fail "cannot create a private checksum."
  }
  if ! kube exec "$pod" -- sh -ec 'exec pg_dump --format=custom --no-owner --no-acl --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' > "$temporary"; then
    rm -f -- "$temporary" "$checksum_temporary"
    fail "pg_dump failed; incomplete output was removed."
  fi
  if [[ ! -s "$temporary" ]] || ! kube exec -i "$pod" -- sh -ec 'pg_restore --file=/dev/null' < "$temporary"; then
    rm -f -- "$temporary" "$checksum_temporary"
    fail "generated PostgreSQL archive failed validation."
  fi
  digest=$(sha256sum -- "$temporary" | cut -d ' ' -f 1)
  printf '%s  %s\n' "$digest" "$base" > "$checksum_temporary"
  if ! ln -- "$temporary" "$archive"; then
    rm -f -- "$temporary" "$checksum_temporary"
    fail "could not publish backup without overwriting an existing file."
  fi
  rm -- "$temporary"
  if ! ln -- "$checksum_temporary" "$archive.sha256"; then
    rm -f -- "$archive" "$checksum_temporary"
    fail "could not publish checksum; the incomplete backup was removed."
  fi
  rm -- "$checksum_temporary"
  printf 'Backup created: %s (mode 600)\n' "$archive"
}

scale_app_down() {
  kube scale deployment/copilot --replicas=0 >/dev/null || return
  kube wait --for=delete pod -l app=cashflow-copilot --timeout=120s >/dev/null
}

restore_archive() {
  local archive=$1
  kube exec -i "$pod" -- sh -ec 'pg_restore --no-owner --no-acl --exit-on-error --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' < "$archive"
}

recreate_database() {
  kube exec "$pod" -- sh -ec '
    dropdb --if-exists --force --username="$POSTGRES_USER" --maintenance-db=postgres "$POSTGRES_DB"
    createdb --username="$POSTGRES_USER" --owner="$POSTGRES_USER" "$POSTGRES_DB"
  '
}

requeue_unprocessed_events() {
  kube exec "$pod" -- sh -ec '
    psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
      --command="UPDATE outbox SET published_at=NULL,last_error=NULL WHERE NOT EXISTS (SELECT 1 FROM processed_event WHERE processed_event.id=outbox.id)"
  '
}

start_app() {
  kube scale deployment/copilot --replicas=1 >/dev/null || return
  kube rollout status deployment/copilot --timeout=240s >/dev/null
}

rollback_database() {
  local safety_archive=$1
  printf 'Restoring the pre-restore database backup: %s\n' "$safety_archive" >&2
  scale_app_down || return 1
  recreate_database || return 1
  restore_archive "$safety_archive" || return 1
  requeue_unprocessed_events || return 1
  start_app || return 1
}

restore_database() {
  local archive=$1 confirmation=${2:-}
  [[ "$confirmation" == "--confirm-restore" ]] || fail "restore replaces database contents; repeat with --confirm-restore."
  archive=$(resolve_archive "$archive")
  verify_local_archive "$archive"
  verify_cluster_archive "$archive"
  require_database || fail "PostgreSQL workload is unavailable."
  local replicas
  replicas=$(kube get deployment/copilot -o jsonpath='{.spec.replicas}')
  [[ "$replicas" == "1" ]] || fail "restore expects exactly one app replica; found ${replicas:-none}."

  local directory base timestamp safety_archive
  directory=$(dirname -- "$archive")
  base=$(basename -- "$archive" .dump)
  timestamp=$(date -u +%Y%m%dT%H%M%SZ)
  safety_archive="$directory/${base}.pre-restore-${timestamp}.dump"
  scale_app_down || {
    start_app || fail "could not stop or restart the app; database was not replaced. Inspect deployment/copilot."
    fail "could not stop the app cleanly; database was not replaced."
  }
  if ! (backup_archive "$safety_archive" && verify_local_archive "$safety_archive"); then
    start_app || fail "pre-restore backup failed and app restart failed; database was not replaced. Inspect deployment/copilot."
    fail "pre-restore backup failed; database was not replaced and app was restarted."
  fi
  if ! recreate_database || ! restore_archive "$archive" || ! requeue_unprocessed_events; then
    printf 'Requested restore failed; attempting automatic database rollback.\n' >&2
    rollback_database "$safety_archive" || fail "automatic rollback failed; app may be stopped or unready. Keep $safety_archive and inspect the PostgreSQL pod and deployment before retrying."
    fail "requested restore failed; the pre-restore database was restored and the app is running."
  fi
  if ! start_app; then
    printf 'Restored database did not become ready; attempting automatic database rollback.\n' >&2
    rollback_database "$safety_archive" || fail "automatic rollback failed; app may be stopped. Keep $safety_archive and inspect the deployment before retrying."
    fail "restored app failed readiness; the pre-restore database was restored and the app is running."
  fi
  printf 'Restore completed. Pre-restore backup retained at: %s\n' "$safety_archive"
}

command=${1:-}
case "$command" in
  backup)
    [[ $# -eq 2 ]] || { usage >&2; exit 2; }
    backup_archive "$2"
    ;;
  verify)
    [[ $# -eq 2 ]] || { usage >&2; exit 2; }
    archive=$(resolve_archive "$2")
    verify_local_archive "$archive"
    verify_cluster_archive "$archive"
    printf 'Backup checksum and PostgreSQL archive are valid: %s\n' "$archive"
    ;;
  restore)
    [[ $# -eq 3 ]] || { usage >&2; exit 2; }
    restore_database "$2" "$3"
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

#!/usr/bin/env bash
set -euo pipefail
: "${CI_PROJECT:?Set an isolated CI project name}"
: "${CI_IMAGE_TAG:?Set a unique CI image tag}"
[[ "$CI_PROJECT" =~ ^cashflow-ci-[a-f0-9]{12}-[0-9]+$ ]] || {
  echo "Refusing an unexpected CI project name." >&2
  exit 1
}
[[ "$CI_IMAGE_TAG" =~ ^build-[0-9]+-[a-f0-9]{12}$ ]] || {
  echo "Refusing an unexpected CI image tag." >&2
  exit 1
}
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES
exec docker compose --env-file /dev/null --project-directory "$root" \
  -p "$CI_PROJECT" -f "$root/compose.yaml" -f "$root/compose.demo.yaml" \
  -f "$root/compose.ci.yaml" "$@"

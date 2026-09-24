#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  printf 'usage: %s vMAJOR.MINOR.PATCH[-rc.N]\n' "$0" >&2
  exit 2
fi
if [[ -n "${GITHUB_PERSONAL_PAT:-}" ]]; then
  token_source=GITHUB_PERSONAL_PAT
elif [[ -n "${GITHUB_TOKEN:-}" ]]; then
  token_source=GITHUB_TOKEN
else
  printf 'release refused: GITHUB_PERSONAL_PAT or GITHUB_TOKEN is required\n' >&2
  exit 2
fi

tag=$1
root=$(git rev-parse --show-toplevel)
if [[ ! -d "$root/.git" ]]; then
  printf 'release refused: run from a standalone clone with complete Git metadata\n' >&2
  exit 2
fi

cd "$root"
dagger call release-guard --tag "$tag"
(cd .dagger && npm run verify)
dagger call publish --tag "$tag" --token="env:$token_source"

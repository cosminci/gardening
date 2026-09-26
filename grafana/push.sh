#!/usr/bin/env bash
# Copies the committed dashboard to Grafana's file-provisioning directory on the NAS. Grafana polls
# that directory (10s default) and loads or updates the dashboard matched by its hardcoded UID — no
# API call, no restart, no live sync back from the Grafana UI.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
scp "$script_dir/plant-journal-dashboard.json" root@tower.lan:/mnt/user/appdata/grafana/provisioning/dashboards/plant-journal-dashboard.json

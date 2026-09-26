# Plant journal Grafana dashboard

`plant-journal-dashboard.json` is dashboard-as-code for the backend's `/metrics` endpoint (see the [observability spec](../specs/changes/observability/proposal.md)): business trends (plant count, watering urgency/cadence, care/repot/substrate/pesticide activity), request rate/errors/duration, and this app's own container CPU/memory alongside JVM-internal detail (heap/non-heap, GC pause, thread count) that the NAS's existing cAdvisor container metrics can't see. It targets the NAS's existing `victoria-metrics` Grafana datasource (hardcoded UID, same convention as the Insights/Infra dashboards) — no datasource variable, since this is one NAS with one Prometheus-compatible instance.

## Push a change

```sh
./push.sh
```

Grafana polls its provisioning directory (10s default) and loads or updates the dashboard matched by its hardcoded UID (`gardening-observability`) — no API call, no restart, no manual UI step. Edit the committed JSON and re-run the script for any dashboard change; don't edit the dashboard in the Grafana UI, since a provisioned dashboard's UI edits aren't saved back to this file.

# <Component> operations

> Standard: Agentic Engineering Standards v1.2.0
>
> Living-doc template for operating the RUNNING service. Pipeline, versioning, release, publish, and
> deploy mechanics live in ci/specs/ — do NOT duplicate them here.

## Alerts

<!-- Each alert: severity, meaning, runbook link. If there is no monitoring yet (home LAN app), say
so in one line and omit the rest. -->

## Scaling characteristics

<!-- What drives load, how it scales (single instance on the NAS), and known limits. -->

## Runtime dependencies

<!-- External systems the running app needs (the SQLite file under /data, the built static assets)
and the failure behaviour of each. -->

## Deployment topology

<!-- Where it runs (NAS / Unraid), instance count, resources, port, the /data volume, and the
LAN + Tailscale exposure — in human terms, not a copy of the container config. The Unraid template
wiring is recorded here at first deploy. -->

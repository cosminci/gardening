# Plant journal on Unraid

The private `ghcr.io/cosminci/plant-journal` image serves the frontend and API on port 8080. Its SQLite journal lives in `/mnt/user/appdata/plant-journal` on the NAS. There is no application login: allow access from the household LAN and tailnet, **not the public internet**.

## Publish a version

Every PR runs the [affected-component build check](../.github/workflows/build.yml) without publishing. Once merged to `main`, run the [publish workflow](../.github/workflows/release.yml) manually from **main** in GitHub Actions. It performs full verification, then publishes one private `linux/amd64` GHCR image with a UTC tag such as `2026.9.24-T170609` and annotates the built commit with the matching Git tag `v2026.9.24-T170609`. Read the exact image tag and digest from the workflow output; the image carries commit/version labels and `/health` reports its version. No `latest` alias is created. Only trusted maintainers should dispatch publishing because the workflow runs code from main with package-write permission.

If the Git tag push fails after the image publishes, use the image's commit label to annotate that same commit with the matching `v`-prefixed tag and push only the tag; the published image tag is not replaced.

## Install once

1. Log the NAS into GHCR with a **read-only** package token. From a trusted workstation with `GITHUB_PERSONAL_RO_PACKAGES_PAT` set, run `printf '%s' "$GITHUB_PERSONAL_RO_PACKAGES_PAT" | ssh root@tower 'docker login ghcr.io --username cosminci --password-stdin'`. Protect the NAS Docker credential store; never paste the token into the template.
2. Import [plant-journal.xml](plant-journal.xml) as an Unraid Docker user template (for example, `scp unraid/plant-journal.xml root@tower:/boot/config/plugins/dockerMan/templates-user/my-plant-journal.xml`). Replace `REPLACE_WITH_PUBLISHED_VERSION` with an **existing** timestamped image tag. Check the `/data` mount and host port; add three container labels to the **saved Unraid template** before applying it: `wud.watch=true`, `wud.tag.include=^\d{4}\.\d{1,2}\.\d{1,2}-T\d{6}$`, and `wud.trigger.exclude=docker.local`. Only the operator applies the template. The appdata directory must be writable by UID/GID `1000:1000`.
3. In the existing WUD container's **NAS-side** configuration, set masked `WUD_REGISTRY_GHCR_PLANTS_USERNAME=cosminci` and `WUD_REGISTRY_GHCR_PLANTS_TOKEN=<read:packages PAT>`. Keep its existing `WUD_TRIGGER_DOCKER_LOCAL` and other containers' settings. Apply WUD's saved template yourself. WUD reports newer timestamped releases for this service without installing them.
4. Include `plant-journal` in Unraid's periodic Appdata Backup. The already-running `1.0.0-rc.2` trial stays untouched until you apply the new template.

## Update and recover

When WUD reports a newer image, **edit the tag in the saved Unraid template and click Apply**. Keep the journal volume and watch-only labels unchanged. Unraid reports pull and startup failures. Check the selected version on the LAN and from a device connected over the tailnet:

```sh
curl -fsS http://tower:8080/health
```

Expect `"status":"ok"` and the selected `"version"`; verify existing journal entries remain available. Do not forward the port on the public router. To recover from an incompatible version, stop the service **in Unraid**, choose a compatible existing plant-journal appdata backup, restore it using Unraid's backup tool while the service is stopped, select its compatible published image tag in the saved template, and click Apply. A previous image alone may not read a newer database. Restoring an older backup can lose entries recorded since that backup; neither recovery nor updates are automatic.

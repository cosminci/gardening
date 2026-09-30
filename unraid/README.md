# Plant journal on Unraid

Operator runbook for installing, updating, and recovering the NAS deployment. See [Deployment topology](../specs/operational.md#deployment-topology) for the image, port, data path, and backup coverage this template configures; see [Pipeline operations](../ci/specs/operational.md) for how publishing works.

## Publish a version

Every PR runs the [affected-component build check](../.github/workflows/build.yml) without publishing. Once merged to `main`, run the [publish workflow](../.github/workflows/release.yml) manually from **main** in GitHub Actions. It performs full verification, then publishes one private `linux/amd64` GHCR image with a UTC tag such as `2026.9.24-T170609` and annotates the built commit with the matching Git tag `v2026.9.24-T170609`. Read the exact image tag and digest from the workflow output; the image carries commit/version labels. No `latest` alias is created. Only trusted maintainers should dispatch publishing because the workflow runs code from main with package-write permission.

If the Git tag push fails after the image publishes, use the image's commit label to annotate that same commit with the matching `v`-prefixed tag and push only the tag; the published image tag is not replaced.

## Install once

1. Log the NAS into GHCR with a **read-only** package token. From a trusted workstation with `GITHUB_PERSONAL_RO_PACKAGES_PAT` set, run `printf '%s' "$GITHUB_PERSONAL_RO_PACKAGES_PAT" | ssh root@tower 'docker login ghcr.io --username cosminci --password-stdin'`. Protect the NAS Docker credential store; never paste the token into the template.
2. Import [plant-journal.xml](plant-journal.xml) as an Unraid Docker user template (for example, `scp unraid/plant-journal.xml root@tower:/boot/config/plugins/dockerMan/templates-user/my-plant-journal.xml`). Replace `REPLACE_WITH_PUBLISHED_VERSION` with an **existing** timestamped image tag. Check the `/data` mount, the `/photos` mount, and host port; add two container labels to the **saved Unraid template** before applying it: `wud.watch=true` and `wud.tag.include=^\d{4}\.\d{1,2}\.\d{1,2}-T\d{6}$`. WUD auto-updates the container to newer stable tags overnight; only the operator applies the template initially. Both the appdata directory and the photos directory must be writable by UID/GID `1000:1000`; the photos directory lives on the array (default `/mnt/user/media/photos/plant-journal`), not in appdata, since photo files are too large for the weekly appdata backup.
3. The `plant-journal` GHCR package is public, so WUD needs no registry credentials for it — it pulls tags anonymously. (The image itself still requires `docker login` per step 1 to actually run it, since Unraid's Docker page pulls under the NAS's own credential store regardless of package visibility.)
4. Include `plant-journal` in Unraid's periodic Appdata Backup. The already-running `1.0.0-rc.2` trial stays untouched until you apply the new template.

## Update and recover

WUD auto-updates the container to newer stable tags overnight (no `wud.trigger.exclude`, unlike the manually-applied containers elsewhere on the NAS) — there is no DB migration or rollback tooling, so a bad release fails silently until noticed. Check the release notes for any pending version before it ships if you want to catch trouble in advance. After an auto-update, confirm the service is reachable on the LAN and from a device connected over the tailnet:

```sh
curl -fsS http://tower:8080/health
```

Expect `"status":"ok"`; verify existing journal entries remain available. Do not forward the port on the public router. To recover from an incompatible version, stop the service **in Unraid**, choose a compatible existing plant-journal appdata backup, restore it using Unraid's backup tool while the service is stopped, select its compatible published image tag in the saved template, and click Apply. A previous image alone may not read a newer database. Restoring an older backup can lose entries recorded since that backup; recovery is never automatic.

# Plant journal on Unraid

The private `ghcr.io/cosminci/plant-journal` image serves the frontend and API on port 8080. Its SQLite journal lives at `/mnt/user/appdata/plant-journal` on the NAS. It has no login: expose the port on the household LAN and tailnet only, with **no public-router port forward**.

## Publish a version

From a clean checkout with complete Git history, create and push an annotated `vMAJOR.MINOR.PATCH` tag on merged `main`, or `vMAJOR.MINOR.PATCH-rc.N` on the implementation branch for a prerelease trial. For example:

```sh
git tag -a v1.0.0-rc.1 -m 'Plant journal release candidate 1'
git push origin v1.0.0-rc.1
```

The [release check](../.github/workflows/release.yml) starts on the tag push, checks that the exact annotated tag identifies the clean checkout and that stable versions belong to merged `main`, runs full verification, and publishes the `linux/amd64` image to private GHCR. It uses the short-lived GitHub Actions token with `packages: write`; never add a package PAT to the workflow. Only trusted maintainers should be allowed to push `v*` tags: tag workflows execute code from the tagged commit with a package-write token.

An already published version is never replaced. Prereleases do not change `latest`; the highest stable version does. If a stable image was published but its `latest` alias failed, run `dagger call repair-latest --tag v1.0.0 --token=env:GITHUB_PERSONAL_PAT` from a clean, full-Git checkout at that tag. The alias is checked against the versioned image digest.

## Install once

1. Log the NAS into GHCR as `cosminci` using a **read-only** package token. From a trusted workstation with `GITHUB_PERSONAL_RO_PACKAGES_PAT` set, run `printf '%s' "$GITHUB_PERSONAL_RO_PACKAGES_PAT" | ssh root@tower 'docker login ghcr.io --username cosminci --password-stdin'`. Docker stores that credential on the NAS for later pulls; restrict root access and never put the token in the plant-journal template or a command argument.
2. Import [plant-journal.xml](plant-journal.xml) into Unraid's Docker user templates, for example with `scp unraid/plant-journal.xml root@tower:/boot/config/plugins/dockerMan/templates-user/my-plant-journal.xml`. In **Docker → Add Container**, select that template and choose the published image tag. For the pre-merge trial set the repository to `ghcr.io/cosminci/plant-journal:1.0.0-rc.1` and **WUD watch** to `false`. The stable template defaults to `1.0.0`, `/data` mapped to `/mnt/user/appdata/plant-journal`, and host port `8080`. Apply the template once the selected image exists.
3. In the existing Unraid WUD container's settings, add masked environment values `WUD_REGISTRY_GHCR_PLANTS_USERNAME=cosminci` and `WUD_REGISTRY_GHCR_PLANTS_TOKEN=<read:packages PAT>`, then apply/restart WUD. Keep its existing `WUD_TRIGGER_DOCKER_LOCAL` setting for other containers. The plant-journal container alone has `wud.trigger.exclude=docker.local`, `wud.watch=true` for stable releases (false for release candidates), and `wud.tag.include=^\d+\.\d+\.\d+$`; inspect those labels after installation. WUD's dashboard at `http://tower:3030` reports newer stable tags but never replaces this container.
4. Ensure Unraid's periodic **Appdata Backup** includes `plant-journal` after first installation. Backups appear as `/mnt/user/cache-backup/ab_YYYYMMDD_HHMMSS/plant-journal.tar.gz`. There is no pre-deploy backup: recovery can lose journal entries made since the selected backup.

Alternatively, the script below can perform first deployment directly without using the Docker Add Container form; the imported template remains available for Unraid administration. Run it as root on the NAS over SSH; it needs Bash, Docker, curl, jq, and a prior GHCR login, but no coding agent:

```sh
ssh root@tower 'bash -s -- deploy 1.0.0-rc.1' < scripts/nas.sh
```

The service requires writable appdata owned by UID/GID `1000:1000`. The script uses bridge networking and host port 8080 and refuses to replace a container whose mount, port, or network differs from that configuration. If these values were intentionally customized, set `PLANT_JOURNAL_DATA_DIR` and `PLANT_JOURNAL_PORT` in the NAS command environment; it still requires bridge networking.

## Deploy, check, recover

From the repository root, explicitly select a **published** version; never deploy `latest`:

```sh
ssh root@tower 'bash -s -- deploy 1.0.0' < scripts/nas.sh
curl -fsS http://192.168.1.3:8080/health | jq -e '.status == "ok" and .version == "1.0.0"'
curl -fsS http://tower:8080/health | jq -e '.status == "ok" and .version == "1.0.0"'
ssh root@tower 'docker inspect plant-journal --format "{{ index .Config.Labels \"wud.trigger.exclude\" }}"'
```

Check the `tower` URL from a device actually connected through the tailnet, not just from the LAN. The deployment pulls before stopping the prior container, preserves `/data`, and only removes the previous container after the new version reports the exact selected version healthy. A failed pull leaves the old container running. A failed start or health check exits nonzero and leaves `plant-journal-previous` for inspection; choose a compatible backup before recovery.

To recover, select both an **older published image** and its compatible **periodic appdata archive**. The archive must contain the journal database; a missing or invalid archive blocks recovery before any replacement:

```sh
ssh root@tower 'bash -s -- recover 1.0.0 /mnt/user/cache-backup/ab_YYYYMMDD_HHMMSS/plant-journal.tar.gz' < scripts/nas.sh
```

Recovery stops the running container, preserves the current appdata in a timestamped `.before-restore.*` directory, restores the selected archive, and only reports success when the chosen version is healthy. Inspect the backup date and image compatibility before running it; the script cannot infer which database migration each weekly backup contains. A failed extraction restores the prior appdata; a failed start or health check restores the prior appdata and attempts to restart the previous container, retaining the failed restore in `.failed-recovery.*`. Either failure exits nonzero. Subsequent releases remain manual decisions even while WUD reports them.

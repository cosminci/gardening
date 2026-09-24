#!/usr/bin/env bash
set -euo pipefail

name=plant-journal
repository=ghcr.io/cosminci/plant-journal
data_dir=${PLANT_JOURNAL_DATA_DIR:-/mnt/user/appdata/plant-journal}
port=${PLANT_JOURNAL_PORT:-8080}
health_attempts=${PLANT_JOURNAL_HEALTH_ATTEMPTS:-30}

fail() {
  printf 'plant-journal: %s\n' "$1" >&2
  exit 1
}

if [[ $# -lt 2 || $# -gt 3 || ( $1 != deploy && $1 != recover ) ]]; then
  fail 'usage: nas.sh deploy VERSION | nas.sh recover VERSION APPDATA_BACKUP_ARCHIVE'
fi
action=$1
version=$2
if [[ ! $version =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.[1-9][0-9]*)?$ ]]; then
  fail 'expected an explicit stable or release-candidate version, not latest'
fi
if [[ ! $port =~ ^[1-9][0-9]*$ || ! $health_attempts =~ ^[1-9][0-9]*$ ]]; then
  fail 'port and health attempts must be positive integers'
fi
if [[ $data_dir != /* || $data_dir == / || -L $data_dir ||
  $data_dir =~ (^|/)\.\.?(/|$) ]]; then
  fail 'journal data must be an absolute, non-symlink directory'
fi
data_dir=${data_dir%/}
if [[ $action == deploy && $# -ne 2 || $action == recover && $# -ne 3 ]]; then
  fail 'usage: nas.sh deploy VERSION | nas.sh recover VERSION APPDATA_BACKUP_ARCHIVE'
fi

image=$repository:$version
watch=false
if [[ $version != *-rc.* ]]; then
  watch=true
fi

container_exists() {
  docker container inspect "$1" >/dev/null 2>&1
}

wait_for_health() {
  local response
  for ((attempt = 1; attempt <= health_attempts; attempt++)); do
    if response=$(curl --fail --silent --show-error --max-time 3 "http://127.0.0.1:$port/health" 2>/dev/null) &&
      printf '%s\n' "$response" |
        jq --exit-status --arg version "$version" '.status == "ok" and .version == $version' >/dev/null; then
      printf 'plant-journal: %s healthy at http://127.0.0.1:%s/\n' "$version" "$port"
      return
    fi
    if ((attempt < health_attempts)); then sleep 2; fi
  done
  printf 'plant-journal: version %s did not become healthy\n' "$version" >&2
  return 1
}

start_container() {
  mkdir -p "$data_dir" || return 1
  chown 1000:1000 "$data_dir" || return 1
  docker run --detach --name "$name" --restart unless-stopped --network bridge \
    --publish "$port:8080" --volume "$data_dir:/data:rw" \
    --label "wud.watch=$watch" \
    --label 'wud.tag.include=^\d+\.\d+\.\d+$' \
    --label wud.trigger.exclude=docker.local \
    "$image" >/dev/null || return 1
  wait_for_health
}

if [[ $action == deploy ]]; then
  if container_exists "$name-previous"; then
    fail 'an earlier deployment failed; recover or inspect plant-journal-previous first'
  fi
  if container_exists "$name"; then
    current_image=$(docker inspect --format '{{.Config.Image}}' "$name")
    current_data=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$name")
    current_port=$(docker inspect --format '{{(index (index .HostConfig.PortBindings "8080/tcp") 0).HostPort}}' "$name")
    current_network=$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$name")
    if [[ $current_image != "$repository":* || $current_data != "$data_dir" ||
      $current_port != "$port" || $current_network != bridge ]]; then
      fail 'existing container differs from the documented Unraid volume, port, or network'
    fi
    if [[ $current_image == "$image" ]]; then fail "version $version is already deployed"; fi
  fi
  docker pull "$image"
  had_previous=false
  if container_exists "$name"; then
    docker stop "$name" >/dev/null
    docker rename "$name" "$name-previous"
    had_previous=true
  fi
  if ! start_container; then
    fail 'deployment failed; the previous container is retained for recovery'
  fi
  if [[ $had_previous == true ]]; then docker rm "$name-previous" >/dev/null; fi
  exit 0
fi

archive=$3
if [[ ! -f $archive ]]; then fail "recovery backup not found: $archive"; fi
entries=$(tar -tzf "$archive") || fail "cannot read recovery backup: $archive"
relative_data_dir=${data_dir#/}
IFS='/' read -r -a data_path_parts <<< "$relative_data_dir"
strip_components=${#data_path_parts[@]}
has_database=false
while IFS= read -r entry; do
  entry=${entry#/}
  entry=${entry#./}
  if [[ $entry != "$relative_data_dir" &&
    $entry != "$relative_data_dir/"* || $entry =~ (^|/)\.\.(/|$) ]]; then
    fail 'recovery archive contains files outside the plant-journal appdata directory'
  fi
  if [[ $entry == "$relative_data_dir/gardening.db" ]]; then has_database=true; fi
done <<< "$entries"
if [[ $has_database != true ]]; then fail 'recovery archive has no journal database'; fi
if container_exists "$name-before-recovery"; then
  fail 'an earlier recovery is unfinished; inspect plant-journal-before-recovery first'
fi

docker pull "$image"
if container_exists "$name"; then docker stop "$name" >/dev/null; fi
mkdir -p "$data_dir"
safety_dir=$data_dir.before-restore.$(date +%s).$$
if [[ -e $safety_dir ]]; then fail "prior data preservation directory already exists: $safety_dir"; fi
mv "$data_dir" "$safety_dir"
mkdir -p "$data_dir"
if ! tar -xzf "$archive" -C "$data_dir" --strip-components="$strip_components"; then
  mv "$data_dir" "$data_dir.failed-restore.$(date +%s)"
  mv "$safety_dir" "$data_dir"
  if container_exists "$name"; then docker start "$name" >/dev/null; fi
  fail 'backup extraction failed; original journal data was restored'
fi
chown -R 1000:1000 "$data_dir"
had_current=false
had_previous=false
if container_exists "$name"; then
  docker rename "$name" "$name-before-recovery"
  had_current=true
fi
if container_exists "$name-previous"; then had_previous=true; fi
if ! start_container; then
  if container_exists "$name"; then docker rm -f "$name" >/dev/null; fi
  failed_dir=$data_dir.failed-recovery.$(date +%s).$$
  mv "$data_dir" "$failed_dir"
  mv "$safety_dir" "$data_dir"
  if [[ $had_previous == true ]]; then
    docker rename "$name-previous" "$name"
    docker start "$name" >/dev/null || fail 'recovery failed; previous container could not restart'
    if [[ $had_current == true ]]; then docker rm "$name-before-recovery" >/dev/null; fi
  elif [[ $had_current == true ]]; then
    docker rename "$name-before-recovery" "$name"
    docker start "$name" >/dev/null || fail 'recovery failed; original container could not restart'
  fi
  fail "recovery failed; original journal restored, failed data retained at $failed_dir"
fi
if [[ $had_current == true ]]; then docker rm "$name-before-recovery" >/dev/null; fi
if [[ $had_previous == true ]]; then docker rm "$name-previous" >/dev/null; fi
printf 'plant-journal: data before restore remains at %s\n' "$safety_dir"

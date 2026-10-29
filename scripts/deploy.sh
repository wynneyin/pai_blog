#!/usr/bin/env bash
# Update an existing deployment; this script never installs or switches runtimes.
set -euo pipefail

fail() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '[deploy] %s\n' "$*"; }

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_dir"
bundle="${1:?Usage: deploy.sh BUNDLE_DIRECTORY COMMIT_SHA}"
commit="${2:?Usage: deploy.sh BUNDLE_DIRECTORY COMMIT_SHA}"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail 'Invalid commit SHA.'
[[ -f "$bundle/commit" && -f "$bundle/frontend.tar.gz" ]] || fail 'Release bundle is incomplete.'
[[ "$(cat "$bundle/commit")" == "$commit" ]] || fail 'Release bundle belongs to another commit.'
[[ "$(git rev-parse HEAD)" == "$commit" ]] || fail 'Server checkout does not match the built commit.'
command -v curl >/dev/null || fail 'curl is required for health checks.'

check_health() {
  local url="${DEPLOY_HEALTHCHECK_URL:-}"
  local args=(-fLSs --max-time 10 --retry 12 --retry-delay 3 --retry-connrefused --retry-all-errors)
  if [[ -z "$url" ]]; then
    local nginx_host=''
    if [[ -f blog-frontend/nginx.conf ]]; then
      nginx_host="$(awk '$1 == "server_name" { for (i=2; i<=NF; i++) { sub(/;$/, "", $i); if ($i ~ /^[A-Za-z0-9.-]+\.[A-Za-z0-9.-]+$/) { print $i; exit } } }' blog-frontend/nginx.conf)"
    fi
    if [[ -n "$nginx_host" ]]; then
      url="http://$nginx_host"
      if grep -Eq '^[[:space:]]*listen[[:space:]]+([^;[:space:]]*:)?443([[:space:];]|$)' blog-frontend/nginx.conf; then
        url="https://$nginx_host"
      fi
      # Use the real virtual host and verify TLS while checking this server locally.
      args+=(--resolve "$nginx_host:80:127.0.0.1" --resolve "$nginx_host:443:127.0.0.1")
    else
      url='http://127.0.0.1'
    fi
  fi
  log "Health check: $url"
  curl "${args[@]}" "${url%/}/" -o /dev/null
  curl "${args[@]}" "${url%/}/api/articles?page=1&pageSize=1" |
    grep -E '"success"[[:space:]]*:[[:space:]]*true' >/dev/null
}

compose_files=''
if command -v docker >/dev/null && docker inspect blog-frontend >/dev/null 2>&1; then
  compose_files="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.config_files" }}' blog-frontend)"
fi

if [[ -n "$compose_files" && "$compose_files" != '<no value>' ]]; then
  log 'Detected existing Docker Compose deployment.'
  docker compose version >/dev/null
  working_dir="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' blog-frontend)"
  [[ -d "$working_dir" && "$(cd "$working_dir" && pwd -P)" == "$repo_dir" ]] ||
    fail 'The existing Docker deployment belongs to a different checkout.'
  project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' blog-frontend)"
  [[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail 'Missing Compose project label.'
  compose=(docker compose --project-name "$project" --parallel 1)
  [[ ! -f .env.prod ]] || compose+=(--env-file .env.prod)
  IFS=',' read -r -a config_files <<< "$compose_files"
  for config_file in "${config_files[@]}"; do
    [[ -f "$config_file" ]] || fail "Compose file is missing: $config_file"
    compose+=(-f "$config_file")
  done

  # All image builds must finish before replacing any running container.
  COMPOSE_BAKE=false "${compose[@]}" build backend frontend
  "${compose[@]}" up -d --no-build --force-recreate backend frontend
  "${compose[@]}" ps
elif command -v pm2 >/dev/null && pm2 describe blog-backend >/dev/null 2>&1; then
  log 'Detected existing PM2 and Nginx deployment.'
  frontend_dir="${DEPLOY_FRONTEND_DIR:-/var/www/blog}"
  [[ -f "$frontend_dir/index.html" ]] || fail "Existing frontend was not found at $frontend_dir."
  [[ -w "$frontend_dir" ]] || fail "Frontend directory is not writable: $frontend_dir"
  command -v npm >/dev/null || fail 'npm is required for the Node backend.'
  pm2 jlist | node -e '
    const fs = require("fs");
    let input = "";
    process.stdin.on("data", chunk => input += chunk);
    process.stdin.on("end", () => {
      const app = JSON.parse(input).find(app => app.name === "blog-backend");
      const expected = fs.realpathSync(process.argv[1] + "/blog-backend-node/src/index.js");
      const actual = app && fs.realpathSync(app.pm2_env.pm_exec_path);
      if (actual !== expected) process.exit(1);
    });
  ' "$repo_dir" || fail 'The PM2 backend belongs to a different checkout.'
  stage="$(mktemp -d)"
  trap 'rm -rf -- "$stage"' EXIT
  tar -xzf "$bundle/frontend.tar.gz" -C "$stage"
  [[ -f "$stage/index.html" ]] || fail 'The built frontend is missing index.html.'

  # Keep the existing PM2 process configuration and its environment variables.
  npm ci --omit=dev --no-audit --no-fund --prefix "$repo_dir/blog-backend-node"
  pm2 reload blog-backend

  # Publish assets first; keep old hashed assets for already-open browser tabs.
  tar -C "$stage" --exclude='./index.html' -cf - . | tar -C "$frontend_dir" -xf -
  install -m 644 "$stage/index.html" "$frontend_dir/.index-$commit.html"
  mv -f -- "$frontend_dir/.index-$commit.html" "$frontend_dir/index.html"
else
  fail 'No existing blog Docker Compose or PM2 deployment was found. No services were replaced.'
fi

log 'Checking the frontend and article API.'
check_health
log "Successfully deployed $commit"

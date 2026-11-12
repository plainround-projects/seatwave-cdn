#!/usr/bin/env bash
# Starts, tests and stops Module 17's CDN stand-in (origin, shield, edge) under the Compose project
# name seatwave-cdn, every port on 127.0.0.1 only (21700 origin, 21701 edge, 21702 shield).
#
# Usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
#   default   a dry run: print the plan, change nothing (and need no Docker)
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question
#
#   up      ./gradlew stackFiles versionAssets; write a new origin secret into .secrets/; start the
#           three containers; wait until the origin, the shield and the edge answer
#   down    remove the project's containers, network and volumes (down -v) and the secret of the
#           stack that is now gone, then check that nothing of the project is left
#   test    up, ./gradlew stackTest, then down, on every path (also when a test fails)
#   status  list the project's containers (read-only)
#
# Guardrails: the only target is the project seatwave-cdn; Docker must answer first or nothing is
# done; every change appends an audit line to build/stack/audit.log; any failure exits non-zero.
set -euo pipefail

readonly PROJECT="seatwave-cdn"
readonly ORIGIN="http://127.0.0.1:21700"
readonly EDGE="http://127.0.0.1:21701"
readonly SHIELD="http://127.0.0.1:21702"
readonly SECRET_FOLDER=".secrets"
readonly SECRET_BYTES=32
readonly WAIT_SECONDS=60
readonly DOCKER_TIMEOUT_SECONDS=10

usage() {
  echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
case "$action" in up|down|test|status) ;; *) usage ;; esac

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")
# "down" names the tools profile too, so a container left by "docker compose run load" goes as well.
compose_all=(docker compose -p "$PROJECT" --profile tools)

echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  ./gradlew -q stackFiles versionAssets; new secret in $SECRET_FOLDER/; ${compose[*]} up -d --quiet-pull; wait for 21700, 21701, 21702" ;;
  down) echo "  ${compose_all[*]} down -v --remove-orphans; remove $SECRET_FOLDER/; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew stackTest, then down -v (always)" ;;
  status) echo "  ${compose[*]} ps --all" ;;
esac
# The dry run comes before the Docker check: it changes nothing, so it needs nothing.
if [[ "$mode" == "dry-run" && "$action" != "status" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi

# macOS has no timeout command, so the check runs in the background and is killed if it hangs.
docker_answers() {
  docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 &
  local checker=$!
  local waited
  for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      wait "$checker"
      return $?
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  return 1
}
if ! command -v docker > /dev/null || ! docker_answers; then
  echo "Docker is not running (or does not answer within ${DOCKER_TIMEOUT_SECONDS} s); nothing was changed." >&2
  exit 1
fi
if [[ "$action" == "status" ]]; then
  "${compose[@]}" ps --all
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/stack
audit() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}

write_secret() {
  # A new random secret for every stack: 32 bytes as 64 hex digits, for the origin, and the same
  # value as an NGINX directive, for the tiers. The folder is ignored by git and never under
  # build/, so "./gradlew clean" cannot delete it under a running stack. The files are readable by
  # the containers' users (on a Linux host a bind mount keeps the host's owner); the value is a
  # throwaway for this lab only and dies with the stack.
  mkdir -p "$SECRET_FOLDER"
  local secret
  secret="$(openssl rand -hex "$SECRET_BYTES")"
  printf '%s\n' "$secret" > "$SECRET_FOLDER/origin-secret"
  printf 'proxy_set_header X-SeatWave-Origin-Secret "%s";\n' "$secret" > "$SECRET_FOLDER/origin-secret.conf"
  # The header as a file, for curl -H @file: the value never appears in a process listing.
  printf 'X-SeatWave-Origin-Secret: %s\n' "$secret" > "$SECRET_FOLDER/origin-secret.header"
  chmod 0755 "$SECRET_FOLDER"
  chmod 0644 "$SECRET_FOLDER"/origin-secret*
}

answers() {
  # Prints the status code of one GET, or 000 when nothing answered.
  curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$@" || true
}

up() {
  ./gradlew -q stackFiles versionAssets
  # A running stack keeps the secret it started with; a new stack gets a new one.
  if [[ -z "$("${compose[@]}" ps -q)" ]]; then
    write_secret
  fi
  "${compose[@]}" up -d --quiet-pull
  audit "up: started $PROJECT"
  local waited
  for (( waited = 0; waited < WAIT_SECONDS; waited++ )); do
    # The origin's counters (with the secret), and each tier's own 404 for /_origin/: all three
    # are up, and no cache was warmed by checking.
    if [[ "$(answers -H "@$SECRET_FOLDER/origin-secret.header" "$ORIGIN/_origin/stats")" == "200" \
       && "$(answers "$SHIELD/_origin/stats")" == "404" \
       && "$(answers "$EDGE/_origin/stats")" == "404" ]]; then
      echo "stack is up: origin, shield and edge answered after ${waited} s"
      return 0
    fi
    sleep 1
  done
  echo "the stack did not answer within ${WAIT_SECONDS} s" >&2
  "${compose[@]}" logs --tail 40 >&2
  return 1
}

down() {
  "${compose_all[@]}" down -v --remove-orphans
  # The secret belonged to the stack just removed; the next up writes a new one.
  rm -rf "$SECRET_FOLDER"
  audit "down: removed $PROJECT and its secret"
  local left=""
  left+="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT") "
  left+="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT") "
  left+="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  if [[ -n "${left// /}" ]]; then
    echo "left behind by $PROJECT: $left" >&2
    return 1
  fi
  echo "nothing of $PROJECT is left"
}

case "$action" in
  up) up ;;
  down) down ;;
  test)
    status=0
    up || status=$?
    if [[ "$status" -eq 0 ]]; then
      ./gradlew stackTest || status=$?
    fi
    down || status=$?
    exit "$status"
    ;;
esac

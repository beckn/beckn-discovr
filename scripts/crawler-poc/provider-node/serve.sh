#!/usr/bin/env bash
# serve.sh — serve this repo over ngrok with one command.
#
#   ./serve.sh start        # serve repo root on :8080 + open ngrok tunnel, prints the URL
#   ./serve.sh start 9000   # ...on a different port
#   ./serve.sh stop         # stop both the static server and ngrok
#   ./serve.sh status       # show what's running + the current public URL
#
# After 'start', run  node publish-ngrok.js  with NGROK_URL set to the printed URL so the
# catalog's internal links + digests match the tunnel.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${2:-8080}"
DOMAIN="magician-aspirin-sympathy.ngrok-free.dev"   # fixed reserved domain (empty = random URL)
RUN="$ROOT/.serve"          # holds pids + logs (untracked)
mkdir -p "$RUN"

ngrok_url() {
  curl -s http://127.0.0.1:4040/api/tunnels 2>/dev/null \
    | grep -oE '"public_url":"https://[^"]+"' | head -1 | cut -d'"' -f4 || true
}

start() {
  command -v ngrok >/dev/null 2>&1 || {
    echo "ngrok not installed. Install it first:  brew install ngrok"
    echo "then authenticate once:  ngrok config add-authtoken <YOUR_TOKEN>"
    exit 1
  }

  if [ -f "$RUN/http.pid" ] && kill -0 "$(cat "$RUN/http.pid")" 2>/dev/null; then
    echo "already running — use './serve.sh status' or './serve.sh stop' first."
    exit 1
  fi
  if lsof -ti "tcp:$PORT" >/dev/null 2>&1; then
    echo "port $PORT is already in use — run './serve.sh stop' first (or pick another port)."
    exit 1
  fi

  echo "starting static server on :$PORT ..."
  # run python directly (no subshell) so $! is the real server pid; --directory sets the root.
  python3 -m http.server "$PORT" --directory "$ROOT" >"$RUN/http.log" 2>&1 &
  echo $! >"$RUN/http.pid"

  echo "starting ngrok tunnel ..."
  if [ -n "$DOMAIN" ]; then
    ngrok http "$PORT" --url="https://$DOMAIN" --log=stdout >"$RUN/ngrok.log" 2>&1 &
  else
    ngrok http "$PORT" --log=stdout >"$RUN/ngrok.log" 2>&1 &
  fi
  echo $! >"$RUN/ngrok.pid"

  # wait for the tunnel URL to appear
  local url=""
  for _ in $(seq 1 20); do
    url="$(ngrok_url || true)"; [ -n "$url" ] && break
    sleep 0.5
  done

  if [ -z "$url" ]; then
    echo "tunnel did not come up — check $RUN/ngrok.log"
    stop
    exit 1
  fi

  echo ""
  echo "  serving  : $ROOT"
  echo "  local    : http://127.0.0.1:$PORT/catalog/catalog-index.json"
  echo "  public   : $url/catalog/catalog-index.json"
  echo ""
  echo "  sync catalog to this URL:"
  echo "    NGROK_URL=$url node publish-ngrok.js"
}

stop() {
  for name in ngrok http; do
    if [ -f "$RUN/$name.pid" ]; then
      pid="$(cat "$RUN/$name.pid")"
      if kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; echo "stopped $name (pid $pid)"; fi
      rm -f "$RUN/$name.pid"
    fi
  done
  # belt-and-suspenders: free the port and kill any ngrok tunnel we started, even if a pid leaked.
  lsof -ti "tcp:$PORT" 2>/dev/null | xargs kill 2>/dev/null || true
  pkill -f "ngrok http $PORT" 2>/dev/null || true
  echo "stopped."
}

status() {
  local up=0
  for name in http ngrok; do
    if [ -f "$RUN/$name.pid" ] && kill -0 "$(cat "$RUN/$name.pid")" 2>/dev/null; then
      echo "  $name: running (pid $(cat "$RUN/$name.pid"))"; up=1
    else
      echo "  $name: stopped"
    fi
  done
  if [ "$up" = 1 ]; then
    url="$(ngrok_url || true)"
    [ -n "$url" ] && echo "  public: $url"
  fi
  return 0
}

case "${1:-}" in
  start)  start ;;
  stop)   stop ;;
  status) status ;;
  *) echo "usage: $0 {start [port]|stop|status}"; exit 1 ;;
esac

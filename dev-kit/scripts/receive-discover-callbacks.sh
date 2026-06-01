#!/usr/bin/env bash
# Unified callback listener for discovr stack.
#
# Routes:
#   POST /on_discover     — print + ACK
#   POST /on_catalog_pull — print + ACK
#   POST /                — on_catalog_pull fallback (until delivery job fix lands)
#
# Usage: ./receive-discover-callbacks.sh [port]
# Default port: 8097

PORT=${1:-8097}

echo "Unified discovr callback listener on http://localhost:$PORT"
echo "  POST /on_discover     → on_discover"
echo "  POST /on_catalog_pull → on_catalog_pull"
echo "Press Ctrl+C to stop."
echo "---"

python3 - <<EOF
import http.server
import json
import sys
import os

PORT = int(os.environ.get('PORT', $PORT))

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length)
        path = self.path.rstrip('/')

        if path == '/on_discover':
            label = 'on_discover'
        elif path == '/on_catalog_pull':
            label = 'on_catalog_pull'
        else:
            label = 'on_catalog_pull (fallback)'

        print(f"\n=== {label} ===")
        try:
            parsed = json.loads(body)
            ctx = parsed.get('context', {})
            msg = parsed.get('message', {})
            action      = ctx.get('action', '')
            message_id  = ctx.get('messageId', '')
            txn_id      = ctx.get('transactionId', '')
            network_id  = ctx.get('networkId', '')
            if 'on_discover' in label:
                catalogs = msg.get('catalogs', [])
                print(f"[{label}] action={action} messageId={message_id} transactionId={txn_id} networkId={network_id} catalogs={len(catalogs)}")
            else:
                status     = msg.get('status', '')
                catalogs   = msg.get('catalogs', [])
                request_id = msg.get('inReplyTo', {}).get('messageId', '')
                print(f"[{label}] action={action} messageId={message_id} transactionId={txn_id} networkId={network_id} requestId={request_id} status={status} catalogs={len(catalogs)}")
            print(json.dumps(parsed, indent=2))
        except Exception:
            print(body.decode())
        print("=" * 40)
        sys.stdout.flush()
        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        pass

server = http.server.HTTPServer(('0.0.0.0', PORT), Handler)
print(f"Listening on port {PORT}", flush=True)
server.serve_forever()
EOF

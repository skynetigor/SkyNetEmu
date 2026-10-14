#!/usr/bin/env python3
"""A stand-in for the telemetry service, to test the app against.

  python3 tools/telemetry-mock/receive.py [--port 8787] [--fail N] [--status 500] [--delay SECONDS] [--out events.ndjson]

Prints every request (size, headers) and every event line after gunzipping, and appends the events to --out. --fail N answers the
first N requests with --status (to see retries and drops); --delay waits before answering (to see that a slow service does not slow the
app). Use it from the phone with `adb reverse tcp:8787 tcp:8787` and, in a debug build, the preference telemetry_endpoint_override
set to http://127.0.0.1:8787/v1/events.
"""
import argparse
import gzip
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

args = None
count = 0


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        global count
        count += 1
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if self.headers.get('Content-Encoding') == 'gzip':
            body = gzip.decompress(body)
        lines = [l for l in body.decode('utf-8').split('\n') if l]
        print(f"[{time.strftime('%H:%M:%S')}] request #{count}: {len(lines)} events, key={self.headers.get('X-SkyNet-Key')!r}", flush=True)
        for line in lines:
            doc = json.loads(line)
            print('  ', doc.get('event.dataset'), doc.get('event.action'), {k: v for k, v in doc.items() if k.startswith('skynet.') and k.split('.')[1] in ('game', 'fps', 'frametime', 'time_to_window_ms', 'time_to_renderer_ms', 'temp', 'exit', 'error')}, flush=True)
        if args.delay:
            time.sleep(args.delay)
        if count <= args.fail:
            self.send_response(args.status)
            self.end_headers()
            print(f'   -> answered {args.status} (failing the first {args.fail})', flush=True)
            return
        if args.out:
            with open(args.out, 'a') as f:
                f.write('\n'.join(lines) + '\n')
        self.send_response(200)
        self.end_headers()

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--port', type=int, default=8787)
    p.add_argument('--fail', type=int, default=0)
    p.add_argument('--status', type=int, default=500)
    p.add_argument('--delay', type=float, default=0)
    p.add_argument('--out', default='')
    args = p.parse_args()
    print(f'listening on :{args.port}', flush=True)
    HTTPServer(('0.0.0.0', args.port), Handler).serve_forever()

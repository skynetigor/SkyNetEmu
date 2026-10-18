#!/usr/bin/env python3
"""Loopback Elasticsearch bulk receiver for manual Android telemetry tests.

Run with --fail N / --status 500 to exercise whole-request retries, or
--item-statuses 201,409,429,400 to exercise mixed bulk results. --delay simulates
slow networking. With adb reverse tcp:8787 tcp:8787, set the debug preference
telemetry_es_host_override to http://127.0.0.1:8787. Credentials are never logged.
"""
import argparse
import gzip
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlsplit

args = None
count = 0
seen = set()


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        global count
        count += 1
        if urlsplit(self.path).path != '/_bulk':
            return self.reply(404, {'error': 'use /_bulk'})
        try:
            body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            if self.headers.get('Content-Encoding') == 'gzip':
                body = gzip.decompress(body)
            lines = body.decode().splitlines()
            if len(lines) % 2 or not body.endswith(b'\n'):
                raise ValueError('expected alternating create/document lines and a final newline')
            entries = [(json.loads(lines[i])['create'], json.loads(lines[i + 1])) for i in range(0, len(lines), 2)]
        except (ValueError, KeyError, OSError) as error:
            return self.reply(400, {'error': str(error)})
        print(f'[{time.strftime("%H:%M:%S")}] request #{count}: {len(entries)} bulk create actions', flush=True)
        if args.delay:
            time.sleep(args.delay)
        if count <= args.fail:
            return self.reply(args.status, {'error': 'injected request failure'})
        items = []
        for i, (action, document) in enumerate(entries):
            identity = (action['_index'], action['_id'])
            status = args.item_statuses[i % len(args.item_statuses)] if args.item_statuses else 409 if identity in seen else 201
            if status == 201:
                seen.add(identity)
                if args.out:
                    with open(args.out, 'a') as out:
                        out.write(json.dumps(document) + '\n')
            items.append({'create': {**action, 'status': status}})
            print(' ', document.get('event.dataset'), document.get('event.action'), '->', status, flush=True)
        self.reply(200, {'errors': any(item['create']['status'] >= 400 for item in items), 'items': items})

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8787)
    parser.add_argument('--fail', type=int, default=0)
    parser.add_argument('--status', type=int, default=500)
    parser.add_argument('--delay', type=float, default=0)
    parser.add_argument('--item-statuses', type=lambda value: [int(item) for item in value.split(',')], default=[])
    parser.add_argument('--out', default='')
    args = parser.parse_args()
    print(f'Elasticsearch bulk mock listening on 127.0.0.1:{args.port}', flush=True)
    HTTPServer(('127.0.0.1', args.port), Handler).serve_forever()

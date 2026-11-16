#!/usr/bin/env python3
"""Loopback-only emulator fixture for the existing API. It never uses a real account or database."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

KEYS = {'test', 'fps', 'flight_time_ms', 'jump_height_cm', 'knee_flexion_contact_l',
        'knee_flexion_contact_r', 'knee_flexion_peak_l', 'knee_flexion_peak_r',
        'fppa_peak_l', 'fppa_peak_r', 'knee_ankle_ratio', 'flexion_asymmetry_pct', 'quality', 'warnings'}
rows = []
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass
    def respond(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
        if self.path == '/api/auth/login':
            self.respond(200, {'token': 'local-test-token', 'user': {'id': 9001, 'name': 'Demo Athlete', 'role': 'athlete', 'club_id': 1}})
        elif self.path == '/api/auth/logout':
            self.respond(200, {})
        elif self.path == '/api/jumps' and self.headers.get('Authorization') == 'Bearer local-test-token':
            assert body.keys() == KEYS, 'Unexpected upload fields'
            row = dict(body, id=len(rows) + 1, created_at='2026-11-16T18:00:00Z')
            rows.append(row)
            print(json.dumps({'event': 'metrics_received', 'count': len(rows), 'fields': sorted(body), 'flight_time_ms': body['flight_time_ms']}), flush=True)
            self.respond(201, {'id': row['id'], 'created_at': row['created_at']})
        else:
            self.respond(401, {'detail': 'Test authentication required'})
    def do_GET(self):
        self.respond(200, rows) if self.path == '/api/jumps' else self.respond(404, {})
HTTPServer(('127.0.0.1', 8765), Handler).serve_forever()

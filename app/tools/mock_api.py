#!/usr/bin/env python3
"""Loopback-only club API fixture. No real accounts, clips or database are used."""
import argparse
from datetime import datetime, timedelta, timezone, date
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import socket
from urllib.parse import parse_qs, urlsplit
from uuid import UUID

KEYS = {'test', 'fps', 'flight_time_ms', 'jump_height_cm', 'knee_flexion_contact_l',
        'knee_flexion_contact_r', 'knee_flexion_peak_l', 'knee_flexion_peak_r',
        'fppa_peak_l', 'fppa_peak_r', 'knee_ankle_ratio', 'flexion_asymmetry_pct', 'quality', 'warnings'}
CHECKIN_KEYS = {'sleep', 'fatigue', 'soreness', 'stress', 'mood', 'training_minutes', 'rpe'}
ATHLETE = 9001


def now():
    return datetime.now(timezone.utc).isoformat()


def fixture(drop_first=False):
    starts = datetime.now(timezone.utc) + timedelta(hours=2)
    return dict(jumps=[], checkins={}, attempts=[], drop_first=drop_first, sessions=[
        dict(id=i, club_id=1, group_id=None, title=title, starts_at=(starts + timedelta(days=i - 1)).isoformat(),
             place='Club hall', test='cmj', trials=3, notes='', created_by=2, cancelled=cancelled)
        for i, title, cancelled in [(1, 'Morning jumps', False), (2, 'Cancelled practice', True)]
    ], comments=[dict(id=1, athlete_id=ATHLETE, author_id=2, author_name='Demo Coach', author_role='coach',
                     jump_id=None, session_id=1, body='Keep the whole body in frame. Let us discuss the next session.',
                     created_at=now(), read_at=None)])


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    @property
    def state(self):
        return self.server.state

    def respond(self, status, value=None):
        data = b'' if status == 204 else json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def dispatch(self, method):
        parsed = urlsplit(self.path)
        path, query = parsed.path, parse_qs(parsed.query)
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
        if path == '/api/auth/login' and method == 'POST':
            return self.respond(200, dict(token='local-test-token', user=dict(id=ATHLETE, name='Demo Athlete', role='athlete', club_id=1)))
        if path == '/_fixture' and method == 'GET':
            return self.respond(200, self.state)
        if self.headers.get('Authorization') != 'Bearer local-test-token':
            return self.respond(401, {'detail': 'Test authentication required'})
        if path == '/api/auth/logout' and method == 'POST':
            return self.respond(204)
        if path == '/api/jumps':
            if method == 'GET':
                return self.respond(200, list(reversed(self.state['jumps'])))
            if method == 'POST':
                assert KEYS <= body.keys() and body.keys() <= KEYS | {'session_id', 'client_id'}
                if body.get('client_id') is not None:
                    UUID(body['client_id'])
                    existing = next((row for row in self.state['jumps'] if row.get('client_id') == body['client_id']), None)
                else:
                    existing = None
                self.state['attempts'].append(body)
                if existing:
                    print(json.dumps(dict(event='repeat_received', client_id=body['client_id'], count=len(self.state['jumps']))), flush=True)
                    return self.respond(200, {key: existing[key] for key in ('id', 'created_at')})
                if body.get('session_id') is not None and not any(s['id'] == body['session_id'] and not s['cancelled'] for s in self.state['sessions']):
                    return self.respond(422, {'detail': 'Session unavailable'})
                row = dict(body, id=len(self.state['jumps']) + 1, athlete_id=ATHLETE, created_at=now())
                self.state['jumps'].append(row)
                print(json.dumps(dict(event='metrics_received', count=len(self.state['jumps']), payload=body)), flush=True)
                if self.state['drop_first']:
                    self.state['drop_first'] = False
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                return self.respond(201, {key: row[key] for key in ('id', 'created_at')})
        if path == '/api/sessions' and method == 'GET':
            rows = [dict(s, jump_count=sum(j.get('session_id') == s['id'] for j in self.state['jumps'])) for s in self.state['sessions']]
            for key, op in [('from', lambda a, b: a >= b), ('to', lambda a, b: a <= b)]:
                if key in query:
                    edge = datetime.fromisoformat(query[key][0].replace('Z', '+00:00'))
                    rows = [s for s in rows if op(datetime.fromisoformat(s['starts_at'].replace('Z', '+00:00')), edge)]
            offset = int(query.get('offset', ['0'])[0]); limit = int(query.get('limit', ['100'])[0])
            assert offset >= 0 and 1 <= limit <= 500
            return self.respond(200, sorted(rows, key=lambda s: s['starts_at'])[offset:offset + limit])
        if path.startswith('/api/me/checkins/') and method == 'PUT':
            day = path.rsplit('/', 1)[1]
            parsed_day = date.fromisoformat(day)
            day = parsed_day.isoformat()
            if not 0 <= (datetime.now(timezone.utc).date() - parsed_day).days <= 6:
                return self.respond(422, {'detail': 'Day outside allowed range'})
            assert body.keys() == CHECKIN_KEYS
            assert all(type(body[k]) is int and 1 <= body[k] <= 5 for k in ('sleep', 'fatigue', 'soreness', 'stress', 'mood'))
            m, r = body['training_minutes'], body['rpe']
            assert (m is None and r is None) or (type(m) is int and 0 <= m <= 600 and type(r) is int and 0 <= r <= 10)
            old = self.state['checkins'].get(day)
            row = dict(body, id=old['id'] if old else len(self.state['checkins']) + 1, athlete_id=ATHLETE, day=day,
                       created_at=old['created_at'] if old else now(), updated_at=now())
            self.state['checkins'][day] = row
            print(json.dumps(dict(event='checkin_received', day=day, payload=body)), flush=True)
            return self.respond(200 if old else 201, row)
        if path == '/api/checkins' and method == 'GET':
            assert int(query.get('athlete_id', [str(ATHLETE)])[0]) == ATHLETE
            return self.respond(200, [row for day, row in sorted(self.state['checkins'].items(), reverse=True)
                                     if query.get('from', ['0000'])[0] <= day <= query.get('to', ['9999'])[0]])
        if path == '/api/me/unread' and method == 'GET':
            return self.respond(200, dict(comments=sum(c['author_id'] != ATHLETE and c['read_at'] is None for c in self.state['comments'])))
        if path == '/api/me/read' and method == 'POST':
            for c in self.state['comments']:
                if c['author_id'] != ATHLETE and c['read_at'] is None:
                    c['read_at'] = now()
            return self.respond(204)
        if path == '/api/comments':
            if method == 'GET':
                assert int(query.get('athlete_id', [str(ATHLETE)])[0]) == ATHLETE
                offset = int(query.get('offset', ['0'])[0]); limit = int(query.get('limit', ['100'])[0])
                assert offset >= 0 and 1 <= limit <= 500
                rows = sorted(self.state['comments'], key=lambda c: (c['created_at'], c['id']), reverse=True)
                return self.respond(200, rows[offset:offset + limit])
            if method == 'POST':
                assert {'athlete_id', 'body'} <= body.keys() <= {'athlete_id', 'body', 'jump_id', 'session_id'}
                assert body['athlete_id'] == ATHLETE and 1 <= len(body['body'].strip()) <= 1000
                if body.get('jump_id') is not None:
                    assert any(j['id'] == body['jump_id'] for j in self.state['jumps'])
                if body.get('session_id') is not None:
                    assert any(s['id'] == body['session_id'] for s in self.state['sessions'])
                row = dict(body, id=len(self.state['comments']) + 1, author_id=ATHLETE, author_name='Demo Athlete', author_role='athlete',
                           jump_id=body.get('jump_id'), session_id=body.get('session_id'), created_at=now(), read_at=None)
                self.state['comments'].append(row)
                return self.respond(201, row)
        return self.respond(404, {})

    def handle_method(self, method):
        try:
            self.dispatch(method)
        except (AssertionError, ValueError, KeyError, TypeError):
            self.respond(422, {'detail': 'Invalid fixture request'})

    def do_GET(self):
        self.handle_method('GET')

    def do_POST(self):
        self.handle_method('POST')

    def do_PUT(self):
        self.handle_method('PUT')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--drop-first-upload', action='store_true')
    args = parser.parse_args()
    server = HTTPServer(('127.0.0.1', 8765), Handler)
    server.state = fixture(args.drop_first_upload)
    server.serve_forever()

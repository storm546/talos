#!/usr/bin/env python3
"""Check the loopback fixture's app contract using an isolated in-memory server."""
from datetime import datetime, timezone
import http.client
import json
from threading import Thread
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4
from mock_api import Handler, HTTPServer, fixture, KEYS


def main():
    server = HTTPServer(('127.0.0.1', 0), Handler)
    server.state = fixture(drop_first=True)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f'http://127.0.0.1:{server.server_port}'

    def call(path, method='GET', body=None, token='local-test-token'):
        data = None if body is None else json.dumps(body).encode()
        request = Request(base + path, data=data, method=method,
                          headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        try:
            with urlopen(request, timeout=5) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except HTTPError as error:
            return error.code, json.loads(error.read())

    try:
        assert call('/api/sessions', token='')[0] == 401
        status, login = call('/api/auth/login', 'POST', {'email': 'fixture', 'password': 'fixture'}, token='')
        assert status == 200 and login['user']['id'] == 9001
        status, sessions = call('/api/sessions?limit=5')
        assert status == 200 and sessions[1]['cancelled'] and sessions[0]['jump_count'] == 0
        metrics = dict.fromkeys(KEYS)
        metrics.update(test="cmj", fps=25, quality=0.9, warnings=[])
        metrics.update(test='cmj', fps=25, quality=.9, warnings=[], session_id=2, client_id=str(uuid4()))
        assert call('/api/jumps', 'POST', metrics)[0] == 422
        metrics['session_id'] = 1
        try:
            call('/api/jumps', 'POST', metrics)
            raise AssertionError('Expected dropped upload response')
        except http.client.RemoteDisconnected:
            pass
        assert len(server.state['jumps']) == 1
        server.state['sessions'][0]['cancelled'] = True
        status, repeated = call('/api/jumps', 'POST', metrics)
        assert status == 200 and repeated['id'] == 1 and len(server.state['jumps']) == 1
        assert call('/api/sessions')[1][0]['jump_count'] == 1
        day = datetime.now(timezone.utc).date().isoformat()
        body = dict(sleep=5, fatigue=4, soreness=3, stress=2, mood=1, training_minutes=None, rpe=None)
        assert call('/api/me/checkins/' + day, 'PUT', body)[0] == 201
        body.update(training_minutes=45, rpe=6)
        assert call('/api/me/checkins/' + day, 'PUT', body)[0] == 200
        assert len(call('/api/checkins?athlete_id=9001&from=' + day + '&to=' + day)[1]) == 1
        body['rpe'] = None
        assert call('/api/me/checkins/' + day, 'PUT', body)[0] == 422
        assert call('/api/me/unread')[1]['comments'] == 1
        assert call('/api/me/read', 'POST')[0] == 204
        assert call('/api/me/unread')[1]['comments'] == 0
        assert call('/api/comments', 'POST', {'athlete_id': 9001, 'body': 'Thanks', 'jump_id': 1})[0] == 201
        comments = call('/api/comments?athlete_id=9001')[1]
        assert len(comments) == 2 and comments[0]['author_role'] == 'athlete' and comments[0]['jump_id'] == 1
        assert comments[1]['author_name'] == 'Demo Coach' and comments[1]['session_id'] == 1
        print('Mock API contract, dropped response, idempotency, check-ins and feedback passed.')
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == '__main__':
    main()

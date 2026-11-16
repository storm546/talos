"""Club workflows, migration safety, consent, exports and administration boundaries."""

import base64
import csv
import hashlib
import io
import json
import sqlite3
from statistics import median

import pytest
from fastapi.testclient import TestClient

from server import main
from server.test_server import METRICS, api, auth, new_user, template, user_id


PRIVATE = [
    ('PATCH', '/api/users/1/consent', {'consent_recorded': True, 'consent_by': 'Parent'}),
    ('GET', '/api/athletes/1/summary', None), ('GET', '/api/me/export', None),
    ('GET', '/api/athletes/1/export.csv', None), ('GET', '/api/clubs', None),
    ('POST', '/api/clubs', {'name': 'Club'}), ('GET', '/api/users', None),
    ('PATCH', '/api/users/1', {'active': False}),
    ('POST', '/api/users/1/password', {'password': 'new-password'}), ('GET', '/api/audit', None),
]


@pytest.mark.parametrize('method,endpoint,body', PRIVATE)
def test_new_endpoints_require_auth(api, method, endpoint, body):
    client, _ = api
    assert client.request(method, endpoint, json=body).status_code == 401


@pytest.mark.parametrize('account', ['coach', 'coach2', 'athlete1'])
@pytest.mark.parametrize('method,endpoint,body', [
    ('GET', '/api/clubs', None), ('POST', '/api/clubs', {'name': 'Forbidden'}), ('GET', '/api/audit', None),
])
def test_admin_only_boundaries(api, auth, account, method, endpoint, body):
    client, _ = api
    assert client.request(method, endpoint, json=body, headers=auth(account)).status_code == 403


@pytest.mark.parametrize('account,target,status', [
    ('athlete1', 'athlete1', 200), ('athlete1', 'athlete2', 403), ('athlete1', 'athlete3', 403),
    ('coach', 'athlete1', 200), ('coach', 'athlete3', 404), ('coach2', 'athlete1', 404),
    ('coach2', 'athlete3', 200), ('admin', 'athlete1', 200), ('admin', 'athlete3', 200),
])
def test_summary_role_and_object_boundaries(api, auth, account, target, status):
    client, path = api
    response = client.get(f'/api/athletes/{user_id(path, target)}/summary', headers=auth(account))
    assert response.status_code == status


@pytest.mark.parametrize('account,target,status', [
    ('athlete1', 'athlete1', 403), ('athlete1', 'athlete3', 403),
    ('coach', 'athlete1', 200), ('coach', 'athlete3', 404), ('coach2', 'athlete1', 404),
    ('coach2', 'athlete3', 200), ('admin', 'athlete1', 200), ('admin', 'athlete3', 200),
])
def test_csv_role_and_object_boundaries(api, auth, account, target, status):
    client, path = api
    response = client.get(f'/api/athletes/{user_id(path, target)}/export.csv', headers=auth(account))
    assert response.status_code == status


@pytest.mark.parametrize('action,body,method', [
    ('consent', {'consent_recorded': True, 'consent_by': 'Parent'}, 'PATCH'),
    ('', {'active': False}, 'PATCH'), ('password', {'password': 'replacement'}, 'POST'),
])
@pytest.mark.parametrize('account,target,status', [
    ('coach', 'athlete1', 200), ('coach', 'athlete3', 404), ('coach2', 'athlete1', 404),
    ('coach', 'coach', 404), ('coach', 'admin', 404), ('athlete1', 'athlete1', 403),
    ('admin', 'athlete3', 200),
])
def test_management_role_and_object_boundaries(api, auth, action, body, method, account, target, status):
    client, path = api
    endpoint = f'/api/users/{user_id(path, target)}' + ('/' + action if action else '')
    response = client.request(method, endpoint, json=body, headers=auth(account))
    assert response.status_code == (204 if status == 200 and action == 'password' else status)


@pytest.mark.parametrize('account', ['coach', 'admin'])
def test_consent_upload_gate_record_and_revoke(api, auth, account):
    client, path = api
    headers = auth(account)
    created = client.post('/api/users', json=new_user(), headers=headers).json()
    athlete_headers = {'Authorization': 'Bearer ' + client.post('/api/auth/login', json={
        'email': 'new@demo.test', 'password': 'long-password'}).json()['token']}
    endpoint = f'/api/users/{created["id"]}/consent'
    refused = client.post('/api/jumps', json=METRICS, headers=athlete_headers)
    assert refused.status_code == 403 and 'Consent' in refused.json()['detail']
    assert client.get('/api/jumps', headers=athlete_headers).json() == []
    response = client.patch(endpoint, json={'consent_recorded': True, 'consent_by': '  Parent Name  '}, headers=headers)
    assert response.status_code == 200
    assert response.json()['consent_by'] == 'Parent Name'
    assert response.json()['consent_at']
    assert client.post('/api/jumps', json=METRICS, headers=athlete_headers).status_code == 201
    assert client.patch(endpoint, json={'consent_recorded': False, 'consent_by': 'Parent Name'}, headers=headers).status_code == 200
    assert client.post('/api/jumps', json=METRICS, headers=athlete_headers).status_code == 403
    # Reading and exporting remain available after withdrawal.
    assert len(client.get('/api/jumps', headers=athlete_headers).json()) == 1
    assert client.get('/api/me/export', headers=athlete_headers).status_code == 200


@pytest.mark.parametrize('body', [
    {'consent_recorded': 'true', 'consent_by': 'Parent'}, {'consent_recorded': True, 'consent_by': '  '},
    {'consent_recorded': True}, {'consent_recorded': True, 'consent_by': 'A', 'extra': True},
])
def test_consent_validation(api, auth, body):
    client, path = api
    assert client.patch(f'/api/users/{user_id(path, "athlete1")}/consent', json=body, headers=auth('admin')).status_code == 422


def test_nonathlete_consent_and_missing_targets(api, auth):
    client, path = api
    headers = auth('admin')
    assert client.patch(f'/api/users/{user_id(path, "coach")}/consent', json={
        'consent_recorded': True, 'consent_by': 'Parent'}, headers=headers).status_code == 422
    for endpoint in ['summary', 'export.csv']:
        for target in [99999, user_id(path, 'coach')]:
            assert client.get(f'/api/athletes/{target}/{endpoint}', headers=headers).status_code == 404
    for method, endpoint, body in [('PATCH', '/api/users/99999', {'active': False}),
                                   ('POST', '/api/users/99999/password', {'password': 'replacement'})]:
        assert client.request(method, endpoint, json=body, headers=headers).status_code == 404


def test_summary_computes_all_numeric_metrics_and_roster_changes(api, auth):
    client, path = api
    headers = auth('coach')
    athlete_id = user_id(path, 'athlete1')
    jumps = list(reversed(client.get(f'/api/jumps?athlete_id={athlete_id}', headers=headers).json()))
    result = client.get(f'/api/athletes/{athlete_id}/summary', headers=headers).json()
    assert result['jump_count'] == 10 and result['baseline_complete']
    assert result['first_jump_at'] == jumps[0]['created_at']
    assert result['last_jump_at'] == jumps[-1]['created_at']
    assert set(result['metrics']) == set(METRICS) - {'test', 'warnings'} | {'knee_flexion_peak_mean'}
    for jump in jumps:
        jump['knee_flexion_peak_mean'] = (jump['knee_flexion_peak_l'] + jump['knee_flexion_peak_r']) / 2
    for key, value in result['metrics'].items():
        baseline = median(jump[key] for jump in jumps[:3])
        recent = median(jump[key] for jump in jumps[-3:])
        assert value['baseline'] == baseline
        assert value['latest'] == jumps[-1][key]
        assert value['last_three_median'] == recent
        assert value['change_absolute'] == pytest.approx(recent - baseline)
        assert value['change_percent'] == pytest.approx((recent - baseline) / baseline * 100)
    roster = client.get('/api/athletes', headers=headers).json()
    athlete = next(row for row in roster if row['id'] == athlete_id)
    assert athlete['consent_recorded'] and athlete['consent_by'] and athlete['consent_at']
    for key, value in athlete['changes'].items():
        assert value == result['metrics'][key]


@pytest.mark.parametrize('count', [0, 1, 2, 3, 4, 7])
def test_summary_empty_partial_null_zero_and_stable_order(api, auth, count):
    client, path = api
    athlete_id = user_id(path, 'athlete1')
    with main.database(path) as db:
        db.execute('DELETE FROM jumps WHERE athlete_id = ?', (athlete_id,))
        for index in range(count):
            metrics = {**METRICS, 'jump_height_cm': None if index < 3 else 20,
                       'knee_ankle_ratio': 0 if index < 3 else 1}
            db.execute('INSERT INTO jumps(athlete_id,created_at,metrics) VALUES (?,?,?)',
                       (athlete_id, '2026-01-01T00:00:00+00:00', json.dumps(metrics)))
    result = client.get(f'/api/athletes/{athlete_id}/summary', headers=auth('athlete1')).json()
    assert result['jump_count'] == count
    assert result['baseline_complete'] == (count >= 3)
    height = result['metrics']['jump_height_cm']
    assert height['baseline'] is None and height['change_absolute'] is None
    assert height['baseline_count'] == 0
    ratio = result['metrics']['knee_ankle_ratio']
    assert ratio['baseline'] == (0 if count else None)
    assert ratio['change_percent'] is None
    if count >= 4:
        assert ratio['latest'] == 1
    if not count:
        assert all(value is None for value in (result['first_jump_at'], result['last_jump_at'], ratio['latest']))


@pytest.mark.parametrize('account', ['coach', 'admin'])
def test_staff_json_export_is_forbidden(api, auth, account):
    client, _ = api
    assert client.get('/api/me/export', headers=auth(account)).status_code == 403


def test_json_export_is_complete_private_and_uncapped(api, auth):
    client, path = api
    headers = auth('athlete1')
    athlete_id = user_id(path, 'athlete1')
    with main.database(path) as db:
        db.executemany('INSERT INTO jumps(athlete_id,created_at,metrics) VALUES (?,?,?)',
                       [(athlete_id, '2026-01-01T00:00:00+00:00', json.dumps(METRICS))] * 501)
    response = client.get('/api/me/export', headers=headers)
    assert response.status_code == 200 and 'attachment' in response.headers['content-disposition']
    body = response.json()
    assert body['user']['id'] == athlete_id and body['user']['consent_recorded']
    assert body['user']['email'] == 'athlete1@demo.test'
    assert len(body['jumps']) == 511
    assert {row['athlete_id'] for row in body['jumps']} == {athlete_id}
    assert not any(key in response.text for key in ['password_hash', 'password_salt', 'token_hash', 'demo1234'])


def test_csv_preserves_numeric_negative_values(api, auth):
    client, _ = api
    athlete_id = client.get('/api/me', headers=auth('athlete1')).json()['id']
    client.post('/api/jumps', json={**METRICS, 'fppa_peak_l': -12.5}, headers=auth('athlete1'))
    response = client.get(f'/api/athletes/{athlete_id}/export.csv', headers=auth('coach'))
    rows = list(csv.DictReader(io.StringIO(response.text)))
    assert rows[-1]['fppa_peak_l'] == '-12.5'


@pytest.mark.parametrize('value', ['=HYPERLINK("https://example.invalid")', '+SUM(1,2)', '-1+2', '@SUM(1)',
                                   '  =1+1', '\t=1+1', '\r=1+1', '\n=1+1', '\ufeff=1+1'])
def test_csv_formula_injection_guard(api, auth, value):
    client, path = api
    athlete_id = user_id(path, 'athlete1')
    with main.database(path) as db:
        db.execute('UPDATE users SET name = ? WHERE id = ?', (value, athlete_id))
    response = client.get(f'/api/athletes/{athlete_id}/export.csv', headers=auth('coach'))
    assert response.status_code == 200 and response.headers['content-type'].startswith('text/csv')
    rows = list(csv.DictReader(io.StringIO(response.text)))
    assert len(rows) == 10
    assert all(row['athlete_name'] == "'" + value for row in rows)
    assert {int(row['athlete_id']) for row in rows} == {athlete_id}
    assert json.loads(rows[0]['warnings']) == []


@pytest.mark.parametrize('account,count', [('admin', 10), ('coach', 8), ('coach2', 2)])
def test_user_listing_scope_and_secret_filter(api, auth, account, count):
    client, _ = api
    response = client.get('/api/users', headers=auth(account))
    rows = response.json()
    assert response.status_code == 200 and len(rows) == count
    assert all(isinstance(row['active'], bool) for row in rows)
    assert not any(word in response.text for word in ['password', 'salt', 'token'])
    if account != 'admin':
        me = client.get('/api/me', headers=auth(account)).json()
        assert {row['club_id'] for row in rows} == {me['club_id']}
    assert client.get('/api/users', headers=auth(account), params={'limit': 1, 'offset': 1}).json() == rows[1:2]


def test_athlete_cannot_list_users(api, auth):
    client, _ = api
    assert client.get('/api/users', headers=auth('athlete1')).status_code == 403


def test_deactivate_reactivate_revokes_existing_tokens_permanently(api, auth):
    client, path = api
    headers = auth('athlete1')
    coach = auth('coach')
    athlete_id = user_id(path, 'athlete1')
    assert client.patch(f'/api/users/{athlete_id}', json={'active': False}, headers=coach).status_code == 200
    assert client.get('/api/me', headers=headers).status_code == 401
    assert client.post('/api/auth/login', json={'email': 'athlete1@demo.test', 'password': 'demo1234'}).status_code == 401
    assert client.patch(f'/api/users/{athlete_id}', json={'active': True}, headers=coach).status_code == 200
    assert client.get('/api/me', headers=headers).status_code == 401
    assert client.post('/api/auth/login', json={'email': 'athlete1@demo.test', 'password': 'demo1234'}).status_code == 200
    # Deactivation retains history and consent.
    assert len(client.get(f'/api/jumps?athlete_id={athlete_id}', headers=coach).json()) == 10


def test_admin_cannot_deactivate_self_but_can_manage_staff(api, auth):
    client, path = api
    headers = auth('admin')
    assert client.patch(f'/api/users/{user_id(path, "admin")}', json={'active': False}, headers=headers).status_code == 403
    coach_headers = auth('coach2')
    assert client.patch(f'/api/users/{user_id(path, "coach2")}', json={'active': False}, headers=headers).status_code == 200
    assert client.get('/api/me', headers=coach_headers).status_code == 401


def test_password_reset_uses_creation_rules_and_revokes_tokens(api, auth):
    client, path = api
    old = auth('athlete1')
    headers = auth('coach')
    endpoint = f'/api/users/{user_id(path, "athlete1")}/password'
    for body in [{'password': 'short'}, {'password': 12345678}, {'password': 'long-password', 'extra': True}]:
        assert client.post(endpoint, json=body, headers=headers).status_code == 422
    assert client.post(endpoint, json={'password': 'new-password'}, headers=headers).status_code == 204
    assert client.get('/api/me', headers=old).status_code == 401
    assert client.post('/api/auth/login', json={'email': 'athlete1@demo.test', 'password': 'demo1234'}).status_code == 401
    assert client.post('/api/auth/login', json={'email': 'athlete1@demo.test', 'password': 'new-password'}).status_code == 200


@pytest.mark.parametrize('mutation', ['deactivate', 'reset'])
def test_login_rechecks_deactivation_and_reset_after_hash(api, monkeypatch, mutation):
    client, path = api
    original = main.password_hash
    def racing_hash(password, salt):
        result = original(password, salt)
        with main.database(path) as db:
            if mutation == 'deactivate':
                db.execute("UPDATE users SET active=0 WHERE email='athlete1@demo.test'")
            else:
                db.execute("UPDATE users SET password_hash=? WHERE email='athlete1@demo.test'", (b'changed',))
        return result
    monkeypatch.setattr(main, 'password_hash', racing_hash)
    assert client.post('/api/auth/login', json={'email': 'athlete1@demo.test', 'password': 'demo1234'}).status_code == 401
    with main.database(path) as db:
        assert db.execute('SELECT COUNT(*) FROM tokens').fetchone()[0] == 0


def test_club_creation_validation_and_duplicate(api, auth):
    client, _ = api
    headers = auth('admin')
    before = client.get('/api/clubs', headers=headers).json()
    assert len(before) == 2
    response = client.post('/api/clubs', json={'name': '  New Club  '}, headers=headers)
    assert response.status_code == 201 and response.json()['name'] == 'New Club'
    assert len(client.get('/api/clubs', headers=headers).json()) == 3
    assert client.post('/api/clubs', json={'name': 'New Club'}, headers=headers).status_code == 409
    for body in [{'name': ' '}, {'name': 'x', 'extra': True}, {'name': 12}]:
        assert client.post('/api/clubs', json=body, headers=headers).status_code == 422


@pytest.mark.parametrize('body', [{'active': 'false'}, {'active': 0}, {'active': False, 'role': 'admin'}, {}])
def test_activation_validation(api, auth, body):
    client, path = api
    assert client.patch(f'/api/users/{user_id(path, "athlete1")}', json=body, headers=auth('admin')).status_code == 422


def test_audit_records_workflows_without_passwords_and_paginates(api, auth):
    client, path = api
    admin = auth('admin')
    athlete = auth('athlete1')
    athlete_id = user_id(path, 'athlete1')
    client.post('/api/auth/login', json={'email': 'unknown@demo.test', 'password': 'never-record-this'})
    client.post('/api/users', json=new_user(), headers=admin)
    client.patch(f'/api/users/{athlete_id}/consent', json={'consent_recorded': True, 'consent_by': 'Parent'}, headers=admin)
    client.get('/api/me/export', headers=athlete)
    client.get(f'/api/athletes/{athlete_id}/export.csv', headers=admin)
    client.delete('/api/me/data', headers=athlete)
    client.post(f'/api/users/{athlete_id}/password', json={'password': 'also-not-recorded'}, headers=admin)
    client.patch(f'/api/users/{athlete_id}', json={'active': False}, headers=admin)
    client.patch(f'/api/users/{athlete_id}', json={'active': True}, headers=admin)
    response = client.get('/api/audit', headers=admin)
    rows = response.json()
    assert response.status_code == 200
    assert [row['id'] for row in rows] == sorted((row['id'] for row in rows), reverse=True)
    assert {row['action'] for row in rows} >= {'login_success', 'login_failure', 'user_created', 'consent_changed',
        'json_export', 'csv_export', 'data_deleted', 'password_reset', 'user_deactivated', 'user_activated'}
    assert all(row['at'] and row['target'] and isinstance(row['detail'], dict) for row in rows)
    assert all(row['actor_id'] == user_id(path, 'admin') for row in rows if row['action'] == 'password_reset')
    for secret in ['never-record-this', 'also-not-recorded', 'long-password', 'demo1234', 'password_hash']:
        assert secret not in response.text
    assert client.get('/api/audit?limit=2&offset=1', headers=admin).json() == rows[1:3]


@pytest.mark.parametrize('endpoint', ['/api/audit', '/api/users'])
@pytest.mark.parametrize('params', [{'limit': 0}, {'limit': 501}, {'offset': -1}, {'offset': '1.5'}])
def test_new_pagination_validation(api, auth, endpoint, params):
    client, _ = api
    assert client.get(endpoint, params=params, headers=auth('admin')).status_code == 422


def test_migration_preserves_old_accounts_tokens_and_jumps(tmp_path):
    path = tmp_path / 'legacy.db'
    with sqlite3.connect(path) as db:
        db.executescript('''
            CREATE TABLE clubs(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
            CREATE TABLE users(id INTEGER PRIMARY KEY, club_id INTEGER NOT NULL REFERENCES clubs(id),
                email TEXT NOT NULL UNIQUE COLLATE NOCASE, name TEXT NOT NULL, role TEXT NOT NULL,
                password_salt BLOB NOT NULL, password_hash BLOB NOT NULL);
            CREATE TABLE tokens(token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), expires_at REAL NOT NULL);
            CREATE TABLE jumps(id INTEGER PRIMARY KEY, athlete_id INTEGER NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, metrics TEXT NOT NULL);
            INSERT INTO clubs VALUES(1, 'Legacy');
        ''')
        salt = b'0123456789abcdef'
        db.execute('INSERT INTO users VALUES(1,1,?,?,?, ?,?)',
                   ('old@demo.test', 'Old Athlete', 'athlete', salt, main.password_hash('old-password', salt)))
        db.execute('INSERT INTO jumps VALUES(1,1,?,?)', ('2026-01-01T00:00:00+00:00', json.dumps(METRICS)))
        db.execute('INSERT INTO tokens VALUES(?,?,?)', (main.token_hash('old-token'), 1, 9999999999))
    for _ in range(2):
        with TestClient(main.create_app(path)) as client:
            headers = {'Authorization': 'Bearer old-token'}
            assert client.get('/api/me', headers=headers).status_code == 200
            assert len(client.get('/api/jumps', headers=headers).json()) == 1
            assert client.post('/api/jumps', json=METRICS, headers=headers).status_code == 403
            assert client.post('/api/auth/login', json={'email': 'old@demo.test', 'password': 'old-password'}).status_code == 200
    with main.database(path) as db:
        user = db.execute('SELECT * FROM users').fetchone()
        assert user['active'] == 1 and user['consent_recorded'] == 0
        assert user['consent_by'] is None and user['consent_at'] is None


@pytest.mark.parametrize('method,endpoint,body', [
    ('GET', '/', None), ('GET', '/static/dashboard.js', None), ('GET', '/docs', None),
    ('GET', '/docs/oauth2-redirect', None),
    ('GET', '/openapi.json', None), ('GET', '/missing', None), ('GET', '/api/users', None),
    ('POST', '/api/auth/login', {}), ('POST', '/api/auth/login', 'x' * 17000),
])
def test_security_headers_on_success_and_errors(api, method, endpoint, body):
    client, _ = api
    response = client.request(method, endpoint, json=body)
    assert response.headers['x-content-type-options'] == 'nosniff'
    assert response.headers['referrer-policy'] == 'no-referrer'
    policy = response.headers['content-security-policy']
    assert "frame-ancestors 'none'" in policy and "object-src 'none'" in policy
    assert "'unsafe-inline'" not in policy
    if endpoint in ('/docs', '/docs/oauth2-redirect'):
        bootstrap = response.text.split('<script>')[1].split('</script>')[0]
        digest = base64.b64encode(hashlib.sha256(bootstrap.encode()).digest()).decode()
        assert 'sha256-' + digest in policy


def test_redoc_still_has_its_required_asset_policy(api):
    client, _ = api
    response = client.get('/redoc')
    assert response.status_code == 200
    policy = response.headers['content-security-policy']
    assert "script-src 'self' https://cdn.jsdelivr.net;" in policy
    assert "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;" in policy
    assert "'unsafe-inline'" not in policy.split('style-src')[0]


def test_internal_error_security_headers(api, auth, monkeypatch):
    client, _ = api
    headers = auth('coach')
    def broken_user(user):
        raise RuntimeError('Do not expose this')
    monkeypatch.setattr(main, 'public_user', broken_user)
    response = client.get('/api/me', headers=headers)
    assert response.status_code == 500
    assert response.headers['x-content-type-options'] == 'nosniff'
    assert response.headers['referrer-policy'] == 'no-referrer'
    assert "script-src 'self';" in response.headers['content-security-policy']
    assert response.json() == {'detail': 'Internal server error'}


def test_openapi_describes_new_endpoints(api):
    client, _ = api
    schema = client.get('/openapi.json').json()
    assert schema['info']['title'] == 'JumpScan Club Platform'
    assert schema['info']['version'] == '2.0.0' and schema['info']['description']
    for _, endpoint, _ in PRIVATE:
        assert endpoint.replace('/users/1', '/users/{target_id}').replace('/athletes/1', '/athletes/{athlete_id}') in schema['paths']


def test_side_view_jump_with_one_measured_leg_is_accepted():
    from server.test_server import METRICS
    side = {**METRICS, "knee_flexion_contact_r": None, "knee_flexion_peak_r": None, "fppa_peak_l": None,
            "fppa_peak_r": None, "knee_ankle_ratio": None, "flexion_asymmetry_pct": None,
            "warnings": ["single_leg_view"]}
    assert main.Jump(**side).knee_flexion_peak_r is None
    row = {**side, "created_at": "2026-11-16T00:00:00Z"}
    assert main.summary([row])["metrics"]["knee_flexion_peak_mean"]["latest"] == side["knee_flexion_peak_l"]

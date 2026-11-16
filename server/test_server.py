"""Exercise API behavior against isolated, seeded SQLite databases."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import asyncio
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time

from fastapi.testclient import TestClient
import pytest

from server import main
from server.seed import seed


METRICS = {
    "test": "cmj", "fps": 60, "flight_time_ms": 400, "jump_height_cm": 19.6,
    "knee_flexion_contact_l": 20, "knee_flexion_contact_r": 21,
    "knee_flexion_peak_l": 70, "knee_flexion_peak_r": 72,
    "fppa_peak_l": 5, "fppa_peak_r": 6, "knee_ankle_ratio": 0.9,
    "flexion_asymmetry_pct": 2.78, "quality": 0.95, "warnings": [],
}


@contextmanager
def temporary_directory():
    with tempfile.TemporaryDirectory(prefix="test-", dir=Path(__file__).parent) as directory:
        yield Path(directory)


@pytest.fixture(scope="module")
def template():
    with temporary_directory() as directory:
        path = directory / "template.db"
        seed(path)
        yield path


@pytest.fixture
def api(template):
    with temporary_directory() as directory:
        path = directory / "test.db"
        shutil.copyfile(template, path)
        with TestClient(main.create_app(path), raise_server_exceptions=False) as client:
            yield client, path


@pytest.fixture
def auth(api):
    client, _ = api
    headers = {}

    def get(account):
        if account not in headers:
            response = client.post("/api/auth/login", json={"email": account + "@demo.test", "password": "demo1234"})
            assert response.status_code == 200, response.text
            headers[account] = {"Authorization": "Bearer " + response.json()["token"]}
        return headers[account]

    return get


def user_id(path, account):
    with main.database(path) as db:
        return db.execute("SELECT id FROM users WHERE email = ?", (account + "@demo.test",)).fetchone()[0]


def new_user(**changes):
    return {"email": "new@demo.test", "name": "New Athlete", "password": "long-password", "role": "athlete", **changes}


def test_login_and_hashed_expiring_token(api):
    client, path = api
    before = time.time()
    response = client.post("/api/auth/login", json={"email": " COACH@DEMO.TEST ", "password": "demo1234"})
    assert response.status_code == 200
    result = response.json()
    assert set(result) == {"token", "user"}
    assert set(result["user"]) == {"id", "name", "role", "club_id"}
    assert result["user"]["role"] == "coach"
    assert len(result["token"]) >= 32
    with main.database(path) as db:
        row = db.execute("SELECT * FROM tokens").fetchone()
        assert row["token_hash"] == hashlib.sha256(result["token"].encode()).hexdigest()
        assert result["token"] not in json.dumps(dict(row))
        assert before + 30 * 86400 <= row["expires_at"] <= time.time() + 30 * 86400
        user = db.execute("SELECT * FROM users WHERE id = ?", (row["user_id"],)).fetchone()
        assert user["password_hash"] != b"demo1234"
        assert len(user["password_salt"]) == 16
    headers = {"Authorization": "Bearer " + result["token"]}
    assert client.get("/api/me", headers=headers).json() == result["user"]


@pytest.mark.parametrize("email,password", [
    ("coach@demo.test", "wrong"), ("missing@demo.test", "demo1234"),
    ("'OR'1'='1@demo.test", "demo1234"),
])
def test_login_failure_is_generic(api, email, password):
    client, _ = api
    response = client.post("/api/auth/login", json={"email": email, "password": password})
    assert response.status_code == 401
    assert response.json() == {"detail": "Invalid email or password"}


def test_existing_and_missing_accounts_use_same_credential_checks(api, monkeypatch):
    client, _ = api
    original_hash, original_compare = main.password_hash, main.hmac.compare_digest
    hashes, comparisons = [], []

    def hash_spy(password, salt):
        hashes.append(password)
        return original_hash(password, salt)

    def compare_spy(actual, expected):
        comparisons.append((len(actual), len(expected)))
        return original_compare(actual, expected)

    monkeypatch.setattr(main, "password_hash", hash_spy)
    monkeypatch.setattr(main.hmac, "compare_digest", compare_spy)
    for email in ("coach@demo.test", "absent@demo.test"):
        assert client.post("/api/auth/login", json={"email": email, "password": "wrong"}).status_code == 401
    assert hashes == ["wrong", "wrong"]
    assert comparisons == [(32, 32), (32, 32)]


def test_rate_limit_persists_and_expires(api):
    client, path = api
    body = {"email": "COACH@demo.test", "password": "wrong"}
    for _ in range(5):
        assert client.post("/api/auth/login", json=body).status_code == 401
    with TestClient(main.create_app(path)) as other:
        blocked = other.post("/api/auth/login", json={**body, "password": "demo1234"})
        assert blocked.status_code == 429
        assert int(blocked.headers["Retry-After"]) > 0
    assert client.post("/api/auth/login", json={"email": "athlete1@demo.test", "password": "demo1234"}).status_code == 200
    with main.database(path) as db:
        db.execute("UPDATE login_failures SET failed_at = ?", (time.time() - 901,))
    assert client.post("/api/auth/login", json={**body, "password": "demo1234"}).status_code == 200


def test_concurrent_failures_cannot_bypass_limit(api):
    client, _ = api

    def attempt(_):
        return client.post("/api/auth/login", json={"email": "absent@demo.test", "password": "wrong"}).status_code

    with ThreadPoolExecutor(max_workers=4) as pool:
        statuses = list(pool.map(attempt, range(8)))
    assert statuses.count(401) == 5
    assert statuses.count(429) == 3


@pytest.mark.parametrize("email,password,status", [
    ("coach@demo.test", "demo1234", 200),
    ("coach@demo.test", "wrong", 401),
    ("absent@demo.test", "wrong", 401),
])
def test_login_hash_runs_without_sqlite_write_lock(api, monkeypatch, email, password, status):
    client, path = api
    original_hash = main.password_hash
    hashes = []

    def hash_without_lock(password, salt):
        with sqlite3.connect(path, timeout=0) as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("INSERT INTO login_failures VALUES ('unrelated@demo.test', ?)", (time.time(),))
        hashes.append(salt)
        return original_hash(password, salt)

    monkeypatch.setattr(main, "password_hash", hash_without_lock)
    response = client.post("/api/auth/login", json={"email": email, "password": password})
    assert response.status_code == status
    assert len(hashes) == 1


def test_login_purges_expired_failures_for_all_emails(api):
    client, path = api
    with main.database(path) as db:
        db.executemany("INSERT INTO login_failures VALUES (?, ?)", [
            ("old1@demo.test", time.time() - 901),
            ("old2@demo.test", time.time() - 1800),
            ("recent@demo.test", time.time()),
        ])
    assert client.post("/api/auth/login", json={"email": "coach@demo.test", "password": "demo1234"}).status_code == 200
    with main.database(path) as db:
        assert [row[0] for row in db.execute("SELECT email FROM login_failures")] == ["recent@demo.test"]


def test_success_resets_failed_login_count(api):
    client, _ = api
    for _ in range(2):
        for _ in range(4):
            assert client.post("/api/auth/login", json={"email": "coach@demo.test", "password": "wrong"}).status_code == 401
        assert client.post("/api/auth/login", json={"email": "coach@demo.test", "password": "demo1234"}).status_code == 200


def test_logout_revokes_only_current_token(api, auth):
    client, _ = api
    first = auth("athlete1")
    second = client.post("/api/auth/login", json={"email": "athlete1@demo.test", "password": "demo1234"}).json()["token"]
    assert client.post("/api/auth/logout", headers=first).status_code == 204
    assert client.get("/api/me", headers=first).status_code == 401
    assert client.get("/api/me", headers={"Authorization": "Bearer " + second}).status_code == 200


def test_expired_and_malformed_tokens(api, auth):
    client, path = api
    headers = auth("coach")
    with main.database(path) as db:
        db.execute("UPDATE tokens SET expires_at = ?", (time.time() - 1,))
    assert client.get("/api/me", headers=headers).status_code == 401
    for value in ("", "Basic abc", "Bearer", "Bearer a b", "Bearer unknown", "Bearer " + "a" * 257):
        assert client.get("/api/me", headers={"Authorization": value}).status_code == 401


@pytest.mark.parametrize("method,path,body", [
    ("GET", "/api/me", None), ("POST", "/api/auth/logout", None),
    ("POST", "/api/users", new_user()), ("GET", "/api/athletes", None),
    ("POST", "/api/jumps", METRICS), ("GET", "/api/jumps", None),
    ("DELETE", "/api/me/data", None),
])
def test_all_private_endpoints_require_token(api, method, path, body):
    client, _ = api
    assert client.request(method, path, json=body).status_code == 401


@pytest.mark.parametrize("account,athlete_count", [("admin", 3), ("coach", 2), ("coach2", 1)])
def test_roster_club_scope_and_aggregates(api, auth, account, athlete_count):
    client, path = api
    headers = auth(account)
    response = client.get("/api/athletes", headers=headers)
    assert response.status_code == 200
    athletes = response.json()
    assert len(athletes) == athlete_count
    caller = client.get("/api/me", headers=headers).json()
    for athlete in athletes:
        assert athlete["jump_count"] == 10
        history = client.get("/api/jumps", params={"athlete_id": athlete["id"]}, headers=headers).json()
        assert athlete["last_jump_at"] == history[0]["created_at"]
        if account != "admin":
            assert athlete["club_id"] == caller["club_id"]
    if account == "admin":
        assert user_id(path, "athlete3") in [a["id"] for a in athletes]


def test_athlete_cannot_list_roster_or_create_users(api, auth):
    client, _ = api
    headers = auth("athlete1")
    assert client.get("/api/athletes", headers=headers).status_code == 403
    assert client.post("/api/users", json=new_user(), headers=headers).status_code == 403


@pytest.mark.parametrize("role", ["admin", "coach", "athlete"])
def test_admin_creates_any_role_in_other_club(api, auth, role):
    client, path = api
    club_id = client.get("/api/me", headers=auth("coach2")).json()["club_id"]
    response = client.post("/api/users", json=new_user(role=role, club_id=club_id), headers=auth("admin"))
    assert response.status_code == 201
    assert response.json()["role"] == role
    assert response.json()["club_id"] == club_id
    login = client.post("/api/auth/login", json={"email": "new@demo.test", "password": "long-password"})
    assert login.status_code == 200
    assert login.json()["user"] == response.json()
    assert user_id(path, "new") == response.json()["id"]


@pytest.mark.parametrize("explicit_club", [False, True])
def test_coach_creates_own_athlete_with_empty_aggregate(api, auth, explicit_club):
    client, _ = api
    headers = auth("coach")
    club_id = client.get("/api/me", headers=headers).json()["club_id"]
    body = new_user(**({"club_id": club_id} if explicit_club else {}))
    response = client.post("/api/users", json=body, headers=headers)
    assert response.status_code == 201
    assert response.json()["club_id"] == club_id
    athlete = next(a for a in client.get("/api/athletes", headers=headers).json() if a["id"] == response.json()["id"])
    assert athlete["jump_count"] == 0 and athlete["last_jump_at"] is None


@pytest.mark.parametrize("role", ["admin", "coach"])
def test_coach_cannot_create_privileged_roles(api, auth, role):
    client, _ = api
    assert client.post("/api/users", json=new_user(role=role), headers=auth("coach")).status_code == 403


def test_coach_cannot_choose_another_club(api, auth):
    client, _ = api
    club_id = client.get("/api/me", headers=auth("coach2")).json()["club_id"]
    assert client.post("/api/users", json=new_user(club_id=club_id), headers=auth("coach")).status_code == 403


def test_duplicate_email_unknown_club_and_safe_sql(api, auth):
    client, _ = api
    headers = auth("admin")
    assert client.post("/api/users", json=new_user(email="COACH@demo.test"), headers=headers).status_code == 409
    assert client.post("/api/users", json=new_user(club_id=9999), headers=headers).status_code == 422
    name = "<img src=x onerror=alert(1)>'); DROP TABLE users; --"
    response = client.post("/api/users", json=new_user(name=name), headers=headers)
    assert response.status_code == 201 and response.json()["name"] == name
    assert len(client.get("/api/athletes", headers=headers).json()) == 4


@pytest.mark.parametrize("body", [
    new_user(role="owner"), new_user(name="  "), new_user(password="short"),
    new_user(club_id="1"), new_user(club_id=True), new_user(email="invalid"),
    new_user(password_hash="injected"), new_user(video="forbidden"),
])
def test_user_validation(api, auth, body):
    client, _ = api
    assert client.post("/api/users", json=body, headers=auth("admin")).status_code == 422


@pytest.mark.parametrize("body", [
    {"email": "coach@demo.test", "password": "demo1234", "role": "admin"},
    {"email": 12, "password": "demo1234"}, {"email": "coach@demo.test", "password": 12},
])
def test_login_validation(api, body):
    client, _ = api
    response = client.post("/api/auth/login", json=body)
    assert response.status_code == 422
    assert "demo1234" not in response.text


def test_upload_returns_ids_and_roundtrips_metrics_newest_first(api, auth):
    client, _ = api
    headers = auth("athlete1")
    me = client.get("/api/me", headers=headers).json()
    first = client.post("/api/jumps", json=METRICS, headers=headers)
    second = client.post("/api/jumps", json={**METRICS, "knee_ankle_ratio": 1.1}, headers=headers)
    assert first.status_code == second.status_code == 201
    assert set(first.json()) == {"id", "created_at"}
    jumps = client.get("/api/jumps", headers=headers).json()
    assert len(jumps) == 12
    assert [jump["id"] for jump in jumps[:2]] == [second.json()["id"], first.json()["id"]]
    assert jumps == sorted(jumps, key=lambda j: (j["created_at"], j["id"]), reverse=True)
    assert all(jump["athlete_id"] == me["id"] for jump in jumps)
    assert {key: jumps[1][key] for key in METRICS} == METRICS


@pytest.mark.parametrize("account", ["admin", "coach", "coach2"])
def test_staff_cannot_upload_or_delete_athlete_data(api, auth, account):
    client, _ = api
    headers = auth(account)
    assert client.post("/api/jumps", json=METRICS, headers=headers).status_code == 403
    assert client.delete("/api/me/data", headers=headers).status_code == 403


@pytest.mark.parametrize("account,count", [("admin", 30), ("coach", 20), ("coach2", 10), ("athlete1", 10)])
def test_unfiltered_history_obeys_scope(api, auth, account, count):
    client, path = api
    response = client.get("/api/jumps", headers=auth(account))
    assert response.status_code == 200
    jumps = response.json()
    assert len(jumps) == count
    allowed = {user_id(path, "athlete1"), user_id(path, "athlete2")} if account == "coach" else None
    if allowed:
        assert {jump["athlete_id"] for jump in jumps} == allowed
    if account == "athlete1":
        assert {jump["athlete_id"] for jump in jumps} == {user_id(path, "athlete1")}


@pytest.mark.parametrize("account,target,status", [
    ("athlete1", "athlete1", 200), ("athlete1", "athlete2", 403), ("athlete1", "athlete3", 403),
    ("coach", "athlete1", 200), ("coach", "athlete3", 404),
    ("coach2", "athlete1", 404), ("coach2", "athlete3", 200),
    ("admin", "athlete1", 200), ("admin", "athlete3", 200),
])
def test_object_access_for_history(api, auth, account, target, status):
    client, path = api
    target_id = user_id(path, target)
    response = client.get("/api/jumps", params={"athlete_id": target_id}, headers=auth(account))
    assert response.status_code == status
    if status == 200:
        assert len(response.json()) == 10
        assert all(jump["athlete_id"] == target_id for jump in response.json())


def test_history_checks_objects_even_when_empty(api, auth):
    client, path = api
    with main.database(path) as db:
        db.execute("DELETE FROM jumps WHERE athlete_id = ?", (user_id(path, "athlete3"),))
    assert client.get("/api/jumps", params={"athlete_id": user_id(path, "athlete3")}, headers=auth("coach")).status_code == 404
    assert client.get("/api/jumps", params={"athlete_id": 99999}, headers=auth("admin")).status_code == 404
    assert client.get("/api/jumps", params={"athlete_id": user_id(path, "coach")}, headers=auth("admin")).status_code == 404
    for value in ("1 OR 1=1", "-1", "0", "1.5"):
        assert client.get("/api/jumps", params={"athlete_id": value}, headers=auth("admin")).status_code == 422


@pytest.mark.parametrize("account,other_athlete", [("coach", "athlete3"), ("coach2", "athlete1")])
def test_coach_history_not_found_is_uniform(api, auth, account, other_athlete):
    client, path = api
    headers = auth(account)
    for target in (99999, user_id(path, "coach"), user_id(path, "admin"), user_id(path, other_athlete)):
        response = client.get("/api/jumps", params={"athlete_id": target}, headers=headers)
        assert response.status_code == 404
        assert response.json() == {"detail": "Athlete not found"}


@pytest.fixture(params=["login", "logout", "add_user", "add_jump", "delete_data"])
def write_request(api, auth, request):
    client, path = api
    operation = request.param
    headers = {} if operation == "login" else auth("coach" if operation == "add_user" else "athlete1")
    method, endpoint, body, table, change, status = {
        "login": ("POST", "/api/auth/login", {"email": "coach@demo.test", "password": "demo1234"}, "tokens", 1, 200),
        "logout": ("POST", "/api/auth/logout", None, "tokens", -1, 204),
        "add_user": ("POST", "/api/users", new_user(), "users", 1, 201),
        "add_jump": ("POST", "/api/jumps", METRICS, "jumps", 1, 201),
        "delete_data": ("DELETE", "/api/me/data", None, "jumps", -10, 204),
    }[operation]
    with main.database(path) as db:
        before = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
    return {"method": method, "url": endpoint, "json": body, "headers": headers}, table, before, change, status


def test_writes_are_durable_before_response_starts(api, write_request):
    client, path = api
    arguments, table, before, change, status = write_request
    observed = []

    async def observe_response(scope, receive, send):
        async def observe_send(message):
            if message["type"] == "http.response.start":
                with main.database(path) as db:
                    observed.append(db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
            await send(message)
        await client.app(scope, receive, observe_send)

    with TestClient(observe_response, raise_server_exceptions=False) as observer:
        response = observer.request(**arguments)
    assert response.status_code == status
    assert observed == [before + change]


def test_commit_failure_cannot_report_success(api, write_request):
    client, path = api
    arguments, table, before, _, _ = write_request

    class FailingCommit(sqlite3.Connection):
        def commit(self):
            if self.total_changes:
                raise sqlite3.OperationalError("simulated commit failure")
            super().commit()

    def failing_db():
        db = sqlite3.connect(path, check_same_thread=False, factory=FailingCommit)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        try:
            yield db
            db.commit()
        finally:
            db.close()

    client.app.dependency_overrides[main.get_db] = failing_db
    try:
        response = client.request(**arguments)
    finally:
        client.app.dependency_overrides.clear()
    assert response.status_code == 500
    assert response.json() == {"detail": "Internal server error"}
    with main.database(path) as db:
        assert db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == before


@pytest.mark.parametrize("endpoint", ["/api/auth/login", "/api/users", "/api/jumps"])
@pytest.mark.parametrize("chunked", [False, True])
def test_oversize_body_is_rejected_before_json_parsing(api, endpoint, chunked):
    client, _ = api
    body = b"{" + b"x" * (16 * 1024)
    content = iter([body[:8192], body[8192:]]) if chunked else body
    response = client.post(endpoint, content=content, headers={"Content-Type": "application/json"})
    assert response.status_code == 413
    assert response.json() == {"detail": "Request body too large"}


def test_body_limit_accepts_exactly_16_kib(api):
    client, _ = api
    body = json.dumps({"email": "coach@demo.test", "password": "demo1234"}).encode()
    body += b" " * (16 * 1024 - len(body))
    assert client.post("/api/auth/login", content=body, headers={"Content-Type": "application/json"}).status_code == 200
    assert client.post("/api/auth/login", content=body + b" ", headers={"Content-Type": "application/json"}).status_code == 413


@pytest.mark.parametrize("content_length,expected_reads", [(b"16385", 0), (None, 2), (b"1", 2)])
def test_body_limit_stops_reading_as_soon_as_oversize(content_length, expected_reads):
    messages, reads = [], []
    chunks = iter([b"x" * 8192, b"x" * 8193, b"unread remainder"])

    async def downstream(scope, receive, send):
        pytest.fail("Oversize request reached the parser")

    async def receive():
        reads.append(True)
        return {"type": "http.request", "body": next(chunks), "more_body": True}

    async def send(message):
        messages.append(message)

    headers = [] if content_length is None else [(b"content-length", content_length)]
    asyncio.run(main.RequestBodyLimit(downstream)({"type": "http", "headers": headers}, receive, send))
    assert messages[0]["status"] == 413
    assert len(reads) == expected_reads


@pytest.mark.parametrize("endpoint", ["/api/jumps", "/api/athletes"])
def test_lists_apply_default_maximum_limit_and_offset_in_sql(api, auth, monkeypatch, endpoint):
    client, path = api
    headers = auth("coach")
    athlete_id = user_id(path, "athlete1")
    other_id = user_id(path, "athlete3")
    with main.database(path) as db:
        if endpoint == "/api/jumps":
            db.executemany("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                           [(athlete_id, "2026-11-16T00:00:00+00:00", json.dumps(METRICS))] * 501)
        else:
            db.executemany(
                "INSERT INTO users(club_id, email, name, role, password_salt, password_hash) "
                "SELECT club_id, ?, ?, role, password_salt, password_hash FROM users WHERE id = ?",
                [(f"page{i}@demo.test", f"Page {i:03}", athlete_id) for i in range(501)],
            )
    original_database = main.database
    statements = []

    @contextmanager
    def traced_database(path):
        with original_database(path) as db:
            db.set_trace_callback(statements.append)
            yield db

    monkeypatch.setattr(main, "database", traced_database)
    default = client.get(endpoint, headers=headers)
    first = client.get(endpoint, params={"limit": 500}, headers=headers)
    following = client.get(endpoint, params={"limit": 500, "offset": 500}, headers=headers)
    small = client.get(endpoint, params={"limit": 2, "offset": 1}, headers=headers)
    assert default.status_code == first.status_code == following.status_code == small.status_code == 200
    assert len(default.json()) == 100
    assert len(first.json()) == 500
    assert len(following.json()) == (21 if endpoint == "/api/jumps" else 3)
    assert default.json() == first.json()[:100]
    assert small.json() == first.json()[1:3]
    assert not {row["id"] for row in first.json()} & {row["id"] for row in following.json()}
    rows = first.json() + following.json()
    assert all(row.get("athlete_id", row["id"]) != other_id for row in rows)
    for clause in ("LIMIT 100 OFFSET 0", "LIMIT 500 OFFSET 0", "LIMIT 500 OFFSET 500", "LIMIT 2 OFFSET 1"):
        assert any(clause in statement for statement in statements)


@pytest.mark.parametrize("endpoint", ["/api/jumps", "/api/athletes"])
@pytest.mark.parametrize("params", [
    {"limit": 0}, {"limit": -1}, {"limit": 501}, {"limit": "1.5"},
    {"offset": -1}, {"offset": "1.5"},
])
def test_list_pagination_rejects_invalid_parameters(api, auth, endpoint, params):
    client, _ = api
    assert client.get(endpoint, params=params, headers=auth("coach")).status_code == 422


RANGES = [
    ("fps", 10, 480), ("flight_time_ms", 0, 1500), ("jump_height_cm", 0, 150),
    ("knee_flexion_contact_l", 0, 180), ("knee_flexion_contact_r", 0, 180),
    ("knee_flexion_peak_l", 0, 180), ("knee_flexion_peak_r", 0, 180),
    ("fppa_peak_l", -90, 90), ("fppa_peak_r", -90, 90),
    ("knee_ankle_ratio", 0, 5), ("flexion_asymmetry_pct", 0, 100), ("quality", 0, 1),
]


@pytest.mark.parametrize("field,low,high", RANGES)
def test_numeric_ranges_reject_outside_and_accept_bounds(api, auth, field, low, high):
    client, path = api
    headers = auth("athlete1")
    for value in (low - 0.01, high + 0.01):
        assert client.post("/api/jumps", json={**METRICS, field: value}, headers=headers).status_code == 422
    for value in (low, high):
        assert client.post("/api/jumps", json={**METRICS, field: value}, headers=headers).status_code == 201
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM jumps").fetchone()[0] == 32


@pytest.mark.parametrize("changes", [
    {"test": "other"}, {"fps": "60"}, {"fps": True}, {"quality": None},
    {"warnings": ["unknown"]}, {"warnings": ["low_fps"] * 6}, {"warnings": "low_fps"},
    {"athlete_id": 2}, {"landmarks": []}, {"video": "data"}, {"image": "data"},
])
def test_jump_validation_rejects_invalid_payloads(api, auth, changes):
    client, _ = api
    assert client.post("/api/jumps", json={**METRICS, **changes}, headers=auth("athlete1")).status_code == 422


def test_nullable_fields_warnings_and_missing_fields(api, auth):
    client, _ = api
    headers = auth("athlete1")
    body = {**METRICS, "test": "drop_jump", "flight_time_ms": None, "jump_height_cm": None,
            "warnings": ["no_flight_detected", "low_fps", "low_visibility"]}
    assert client.post("/api/jumps", json=body, headers=headers).status_code == 201
    for field in METRICS:
        assert client.post("/api/jumps", json={k: v for k, v in METRICS.items() if k != field}, headers=headers).status_code == 422


@pytest.mark.parametrize("value", ["NaN", "Infinity", "-Infinity"])
def test_nonfinite_numbers_return_validation_errors(api, auth, value):
    client, _ = api
    body = json.dumps(METRICS).replace('"fps": 60', '"fps": ' + value)
    response = client.post("/api/jumps", content=body, headers={**auth("athlete1"), "Content-Type": "application/json"})
    assert response.status_code == 422
    assert "Traceback" not in response.text


def test_delete_data_removes_only_callers_jumps(api, auth):
    client, path = api
    with main.database(path) as db:
        before = [dict(row) for row in db.execute("SELECT * FROM jumps WHERE athlete_id != ? ORDER BY id", (user_id(path, "athlete1"),))]
    headers = auth("athlete1")
    assert client.delete("/api/me/data", headers=headers).status_code == 204
    assert client.get("/api/jumps", headers=headers).json() == []
    assert client.get("/api/me", headers=headers).status_code == 200
    with main.database(path) as db:
        after = [dict(row) for row in db.execute("SELECT * FROM jumps ORDER BY id")]
    assert after == before
    roster = client.get("/api/athletes", headers=auth("coach")).json()
    deleted = next(a for a in roster if a["id"] == user_id(path, "athlete1"))
    assert deleted["jump_count"] == 0 and deleted["last_jump_at"] is None
    assert client.delete("/api/me/data", headers=headers).status_code == 204


def test_dashboard_is_public_static_and_uses_safe_dom(api):
    client, _ = api
    response = client.get("/")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/html")
    assert response.headers["cache-control"] == "no-store"
    html = response.text
    for expected in ("/api/auth/login", "/api/athletes", "/api/jumps?athlete_id=", "knee_ankle_ratio", "knee_flexion_peak_l", "knee_flexion_peak_r", "textContent"):
        assert expected in html
    assert "innerHTML" not in html
    assert "localStorage" not in html


def test_internal_errors_do_not_expose_tracebacks(api, auth, monkeypatch):
    client, _ = api
    headers = auth("coach")

    def broken_public_user(user):
        raise RuntimeError("private internal details")

    monkeypatch.setattr(main, "public_user", broken_public_user)
    response = client.get("/api/me", headers=headers)
    assert response.status_code == 500
    assert response.json() == {"detail": "Internal server error"}


def test_seed_command_is_idempotent_and_environment_selects_database(monkeypatch):
    with temporary_directory() as directory:
        path = directory / "seed.db"
        monkeypatch.setenv("JUMPSCAN_DB", str(path))
        for _ in range(2):
            result = subprocess.run([sys.executable, "-m", "server.seed"], capture_output=True, text=True, check=True)
            assert "Synthetic demo data" in result.stdout
        with main.database(path) as db:
            assert db.execute("SELECT COUNT(*) FROM clubs").fetchone()[0] == 2
            assert db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 6
            assert db.execute("SELECT COUNT(*) FROM jumps").fetchone()[0] == 30
            for row in db.execute("SELECT metrics FROM jumps"):
                main.Jump.model_validate_json(row[0])
        with TestClient(main.create_app()) as client:
            response = client.post("/api/auth/login", json={"email": "admin@demo.test", "password": "demo1234"})
            assert response.status_code == 200

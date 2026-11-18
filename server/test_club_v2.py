"""Club platform: groups, sessions, check-ins, feedback, events, iCalendar, security."""

import csv
import io
import json
import sqlite3
import time
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from server import main
from server.test_server import METRICS, api, auth, new_user, template, user_id


CHECKIN = {"sleep": 4, "fatigue": 4, "soreness": 4, "stress": 4, "mood": 4}


def today():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def group_id(path, name="Юноши"):
    with main.database(path) as db:
        return db.execute("SELECT id FROM groups WHERE name = ?", (name,)).fetchone()[0]


def session_ids(path):
    with main.database(path) as db:
        return [row["id"] for row in db.execute("SELECT id FROM sessions ORDER BY id")]


def club2_id(path):
    with main.database(path) as db:
        return db.execute("SELECT id FROM clubs WHERE name = 'Втори демо клуб'").fetchone()[0]


# --- groups ---

def test_groups_list_scope_and_members(api, auth):
    client, path = api
    admin = client.get("/api/groups", headers=auth("admin")).json()
    assert len(admin) == 2
    assert "member_ids" in admin[0]
    coach = client.get("/api/groups", headers=auth("coach")).json()
    assert {g["club_id"] for g in coach} == {client.get("/api/me", headers=auth("coach")).json()["club_id"]}
    assert all("member_ids" in g for g in coach)
    coach2 = client.get("/api/groups", headers=auth("coach2")).json()
    assert coach2 == []
    athlete = client.get("/api/groups", headers=auth("athlete1")).json()
    assert athlete and "member_ids" not in athlete[0]


def test_group_create_roles_and_club_scope(api, auth):
    client, path = api
    coach = auth("coach")
    coach_club = client.get("/api/me", headers=coach).json()["club_id"]
    other_club = club2_id(path)
    assert client.post("/api/groups", json={"name": "Нова"}, headers=coach).status_code == 201
    assert client.post("/api/groups", json={"name": "Извън", "club_id": other_club}, headers=coach).status_code == 403
    assert client.post("/api/groups", json={"name": "Админ", "club_id": other_club}, headers=auth("admin")).status_code == 201
    assert client.post("/api/groups", json={"name": "Група"}, headers=auth("athlete1")).status_code == 403
    for bad in ({"name": " "}, {"name": "x" * 81}, {"name": "x", "extra": True}, {"name": 1}):
        assert client.post("/api/groups", json=bad, headers=coach).status_code == 422


def test_group_rename_delete_and_member_surgery(api, auth):
    client, path = api
    coach = auth("coach")
    gid = group_id(path)
    assert client.patch(f"/api/groups/{gid}", json={"name": "Преименувана"}, headers=coach).status_code == 200
    athlete1, athlete2 = user_id(path, "athlete1"), user_id(path, "athlete2")
    assert client.put(f"/api/groups/{gid}/members", json={"athlete_ids": [athlete1, athlete2]},
                      headers=coach).status_code == 200
    other_club_athlete = user_id(path, "athlete3")
    assert client.put(f"/api/groups/{gid}/members", json={"athlete_ids": [other_club_athlete]},
                      headers=coach).status_code == 422
    assert client.put(f"/api/groups/{gid}/members", json={"athlete_ids": [user_id(path, "coach")]},
                      headers=coach).status_code == 422
    assert client.delete(f"/api/groups/{gid}", headers=coach).status_code == 204
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM groups WHERE id = ?", (gid,)).fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM group_members WHERE group_id = ?", (gid,)).fetchone()[0] == 0
    # coach2 cannot touch club-1 groups -> 404
    assert client.patch(f"/api/groups/{group_id(path, 'Състезатели')}", json={"name": "X"},
                        headers=auth("coach2")).status_code == 404


def test_group_cross_club_404(api, auth):
    client, path = api
    gid = group_id(path)
    for method, endpoint in (("PATCH", f"/api/groups/{gid}"), ("DELETE", f"/api/groups/{gid}")):
        assert client.request(method, endpoint, json={"name": "Y"} if method == "PATCH" else None,
                              headers=auth("coach2")).status_code == 404
    assert client.put(f"/api/groups/{gid}/members", json={"athlete_ids": []},
                      headers=auth("coach2")).status_code == 404


# --- sessions ---

def session_body(**over):
    body = {"title": "Тестова сесия", "starts_at": "2026-12-01T10:00:00+00:00", "test": "cmj",
            "trials": 3, "place": "Зала", "notes": ""}
    body.update(over)
    return body


def test_session_create_edit_scope(api, auth):
    client, path = api
    coach = auth("coach")
    coach2 = auth("coach2")
    gid = group_id(path)
    created = client.post("/api/sessions", json=session_body(group_id=gid), headers=coach)
    assert created.status_code == 201
    sid = created.json()["id"]
    assert client.patch(f"/api/sessions/{sid}", json=session_body(group_id=gid, cancelled=True),
                        headers=coach).status_code == 200
    assert client.patch(f"/api/sessions/{sid}", json=session_body(), headers=coach2).status_code == 404
    assert client.post("/api/sessions", json=session_body(), headers=auth("athlete1")).status_code == 403
    # group in another club -> 422
    other = client.post("/api/groups", json={"name": "Друга", "club_id": club2_id(path)}, headers=auth("admin")).json()
    assert client.post("/api/sessions", json=session_body(group_id=other["id"]), headers=coach).status_code == 422
    for bad in ({}, {"title": " ", "starts_at": "2026-12-01T10:00:00+00:00", "test": "cmj"},
                {"title": "x", "starts_at": "not-a-date", "test": "cmj"},
                {"title": "x", "starts_at": "2026-12-01T10:00:00+00:00", "test": "other"},
                {"title": "x", "starts_at": "2026-12-01T10:00:00+00:00", "test": "cmj", "trials": 6},
                {**session_body(), "extra": True}):
        assert client.post("/api/sessions", json=bad, headers=coach).status_code == 422


def test_session_visibility(api, auth):
    client, path = api
    coach = auth("coach")
    admin = auth("admin")
    athlete1 = auth("athlete1")  # in Юноши
    athlete5 = auth("athlete5")  # in Състезатели
    athlete3 = auth("athlete3")  # in the other club
    gid = group_id(path)  # Юноши
    sid = client.post("/api/sessions", json=session_body(group_id=gid), headers=coach).json()["id"]
    # A member of Юноши sees it; an athlete of Състезатели and one of another club do not.
    assert sid in {s["id"] for s in client.get("/api/sessions", headers=athlete1).json()}
    assert sid not in {s["id"] for s in client.get("/api/sessions", headers=athlete5).json()}
    # add athlete5 to the group, now visible
    client.put(f"/api/groups/{gid}/members", json={"athlete_ids": [user_id(path, "athlete5")]}, headers=coach)
    assert sid in {s["id"] for s in client.get("/api/sessions", headers=athlete5).json()}
    assert sid in {s["id"] for s in client.get("/api/sessions", headers=coach).json()}
    assert sid in {s["id"] for s in client.get("/api/sessions", headers=admin).json()}
    assert sid not in {s["id"] for s in client.get("/api/sessions", headers=athlete3).json()}


def test_session_jump_count_and_report(api, auth):
    client, path = api
    coach = auth("coach")
    athlete1 = auth("athlete1")
    sids = session_ids(path)
    report = client.get(f"/api/sessions/{sids[0]}/report", headers=coach)
    assert report.status_code == 200
    body = report.json()
    assert body["trials"] == 3
    rows = {row["athlete_id"]: row for row in body["athletes"]}
    assert "knee_flexion_peak_l" in next(iter(rows.values()))
    sessions = client.get("/api/sessions", headers=coach).json()
    by_id = {s["id"]: s for s in sessions}
    assert isinstance(by_id[sids[0]]["jump_count"], int)
    # athlete sees their own jump_count within the session
    mine = next(s for s in client.get("/api/sessions", headers=athlete1).json() if s["id"] == sids[0])
    assert mine["jump_count"] >= 0


def test_session_report_medians_and_trials(api, auth):
    client, path = api
    coach = auth("coach")
    gid = group_id(path)
    athlete1 = user_id(path, "athlete1")
    client.put(f"/api/groups/{gid}/members", json={"athlete_ids": [athlete1]}, headers=coach)
    sid = client.post("/api/sessions", json=session_body(group_id=gid), headers=coach).json()["id"]
    client.post("/api/users/1/consent", json={"consent_recorded": True, "consent_by": "T"}, headers=auth("admin"))
    h = auth("athlete1")
    for ratio in (0.8, 1.0, 1.2):
        client.post("/api/jumps", json={**METRICS, "knee_ankle_ratio": ratio, "session_id": sid}, headers=h)
    report = client.get(f"/api/sessions/{sid}/report", headers=coach).json()
    row = next(r for r in report["athletes"] if r["athlete_id"] == athlete1)
    assert row["trials_done"] == 3 and row["trials"] == 3
    assert row["knee_ankle_ratio"] == pytest.approx(1.0)
    # athlete (own row only) cannot read another group's report -> 404 unless member
    assert client.get(f"/api/sessions/{sid}/report", headers=h).status_code == 200


def test_cancelled_session_rejects_upload(api, auth):
    client, path = api
    coach = auth("coach")
    athlete1 = auth("athlete1")
    gid = group_id(path)
    client.put(f"/api/groups/{gid}/members", json={"athlete_ids": [user_id(path, "athlete1")]}, headers=coach)
    sid = client.post("/api/sessions", json=session_body(group_id=gid, cancelled=True), headers=coach).json()["id"]
    assert client.post("/api/jumps", json={**METRICS, "session_id": sid}, headers=athlete1).status_code == 422


# --- idempotent uploads ---

def test_idempotent_upload_by_client_id(api, auth):
    client, path = api
    athlete1 = auth("athlete1")
    athlete2 = auth("athlete2")
    cid = str(uuid.uuid4())
    first = client.post("/api/jumps", json={**METRICS, "client_id": cid}, headers=athlete1)
    assert first.status_code == 201
    repeat = client.post("/api/jumps", json={**METRICS, "client_id": cid}, headers=athlete1)
    assert repeat.status_code == 200
    assert repeat.json() == first.json()
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM jumps WHERE client_id = ?", (cid,)).fetchone()[0] == 1
    other = client.post("/api/jumps", json={**METRICS, "client_id": cid}, headers=athlete2)
    assert other.status_code == 201
    assert other.json()["id"] != first.json()["id"]
    for bad in ("", "x" * 129, 12):
        assert client.post("/api/jumps", json={**METRICS, "client_id": bad}, headers=athlete1).status_code == 422


def test_invalid_session_upload_and_unknown_fields(api, auth):
    client, path = api
    athlete1 = auth("athlete1")
    assert client.post("/api/jumps", json={**METRICS, "session_id": 99999}, headers=athlete1).status_code == 404
    assert client.post("/api/jumps", json={**METRICS, "bogus": 1}, headers=athlete1).status_code == 422


# --- check-ins ---

def test_checkin_gate_day_window_and_replace(api, auth):
    client, path = api
    athlete1 = auth("athlete1")
    today_str = today()
    aid = user_id(path, "athlete1")
    with main.database(path) as db:
        db.execute("DELETE FROM checkins WHERE athlete_id = ? AND day = ?", (aid, today_str))
        db.commit()
    body = {**CHECKIN, "training_minutes": 45, "rpe": 5}
    assert client.put(f"/api/me/checkins/{today_str}", json=body, headers=athlete1).status_code == 201
    assert client.put(f"/api/me/checkins/{today_str}", json=body, headers=athlete1).status_code == 200
    future = (datetime.now(timezone.utc) + timedelta(days=3)).strftime("%Y-%m-%d")
    assert client.put(f"/api/me/checkins/{future}", json=CHECKIN, headers=athlete1).status_code == 422
    old = (datetime.now(timezone.utc) - timedelta(days=10)).strftime("%Y-%m-%d")
    assert client.put(f"/api/me/checkins/{old}", json=CHECKIN, headers=athlete1).status_code == 422
    assert client.put("/api/me/checkins/2026-13-40", json=CHECKIN, headers=athlete1).status_code == 422
    for bad in ({**CHECKIN, "sleep": 6}, {**CHECKIN, "mood": 0}, {**CHECKIN, "training_minutes": 45},
                {**CHECKIN, "rpe": 5}, {**CHECKIN, "rpe": 11}, {**CHECKIN, "extra": 1}, {}):
        assert client.put(f"/api/me/checkins/{today_str}", json=bad, headers=athlete1).status_code == 422


def test_checkin_consent_gate(api, auth):
    client, path = api
    headers = auth("coach")
    created = client.post("/api/users", json=new_user(), headers=headers).json()
    h = {"Authorization": "Bearer " + client.post("/api/auth/login", json={
        "email": "new@demo.test", "password": "long-password"}).json()["token"]}
    assert client.put(f"/api/me/checkins/{today()}", json=CHECKIN, headers=h).status_code == 403


def test_checkins_list_scope(api, auth):
    client, path = api
    coach = auth("coach")
    athlete1 = auth("athlete1")
    coach2 = auth("coach2")
    mine = client.get("/api/checkins", headers=athlete1).json()
    assert all(c["athlete_id"] == user_id(path, "athlete1") for c in mine)
    assert 14 == len(mine)
    team = client.get("/api/checkins", headers=coach).json()
    assert all(c["athlete_id"] != user_id(path, "athlete3") for c in team)
    other = client.get("/api/checkins", params={"athlete_id": user_id(path, "athlete1")},
                       headers=coach2)
    assert other.status_code == 404
    assert client.get("/api/checkins", params={"athlete_id": user_id(path, "athlete1")},
                      headers=athlete1).status_code == 200
    assert client.get("/api/checkins", params={"athlete_id": user_id(path, "athlete2")},
                      headers=athlete1).status_code == 403


def test_team_checkins_below_usual(api, auth):
    client, path = api
    coach = auth("coach")
    team = client.get("/api/checkins/team", headers=coach).json()
    assert len(team) == 6
    athlete2 = user_id(path, "athlete2")
    row = next(r for r in team if r["athlete_id"] == athlete2)
    assert row["checkin"] is not None and row["wellbeing"] is not None
    assert row["below_usual"] is True
    others = [r for r in team if r["athlete_id"] != athlete2]
    assert all(r["below_usual"] is not True for r in others)
    assert client.get("/api/checkins/team", headers=auth("athlete1")).status_code == 403


# --- comments ---

def test_comments_thread_and_unread(api, auth):
    client, path = api
    coach = auth("coach")
    athlete1 = auth("athlete1")
    aid = user_id(path, "athlete1")
    assert client.post("/api/comments", json={"athlete_id": aid, "body": "Хей"}, headers=coach).status_code == 201
    assert client.post("/api/comments", json={"athlete_id": aid, "body": ""}, headers=coach).status_code == 422
    assert client.post("/api/comments", json={"athlete_id": aid, "body": "x" * 1001}, headers=coach).status_code == 422
    thread = client.get("/api/comments", params={"athlete_id": aid}, headers=coach).json()
    assert thread and {"author_name", "author_role"} <= set(thread[0])
    unread = client.get("/api/me/unread", headers=athlete1).json()
    assert unread["comments"] >= 2
    assert client.post("/api/me/read", headers=athlete1).status_code == 204
    assert client.get("/api/me/unread", headers=athlete1).json()["comments"] == 0


def test_comment_scope_and_references(api, auth):
    client, path = api
    coach = auth("coach")
    coach2 = auth("coach2")
    athlete1 = auth("athlete1")
    aid = user_id(path, "athlete1")
    a3 = user_id(path, "athlete3")
    assert client.post("/api/comments", json={"athlete_id": a3, "body": "x"}, headers=coach).status_code == 404
    assert client.post("/api/comments", json={"athlete_id": aid, "body": "x"}, headers=coach2).status_code == 404
    assert client.post("/api/comments", json={"athlete_id": a3, "body": "x"}, headers=athlete1).status_code == 403
    assert client.post("/api/comments", json={"athlete_id": aid, "jump_id": 99999, "body": "x"},
                       headers=coach).status_code == 422
    assert client.get("/api/comments", params={"athlete_id": a3}, headers=athlete1).status_code == 403
    # athlete replying to themselves
    assert client.post("/api/comments", json={"athlete_id": aid, "body": "Благодаря"}, headers=athlete1).status_code == 201


# --- iCalendar ---

def test_calendar_format(api, auth):
    client, path = api
    coach = auth("coach")
    res = client.get("/api/me/calendar.ics", headers=coach)
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("text/calendar")
    text = res.text
    assert "\r\n" in text and "\n" not in text.replace("\r\n", "")
    for line in text.split("\r\n"):
        assert len(line.encode()) <= 75
    assert text.startswith("BEGIN:VCALENDAR\r\n")
    assert text.rstrip().endswith("END:VCALENDAR")
    assert "STATUS:CANCELLED" in text
    assert any("UID:session-" in line and line.endswith("@jumpscan") for line in text.split("\r\n"))
    assert "DTSTART:" in text and "DURATION:PT1H" in text


def test_calendar_escaping_and_folding(api, auth):
    client, path = api
    coach = auth("coach")
    with main.database(path) as db:
        db.execute("UPDATE sessions SET title = ?, place = ? WHERE id = ?",
                   ("Точка, запетая; наклонена\\ черта", "Зала; №1, ет.2", session_ids(path)[0]))
    res = client.get("/api/me/calendar.ics", headers=coach)
    assert "Точка\\, запетая\\; наклонена\\\\ черта" in res.text
    # long summary folds
    with main.database(path) as db:
        db.execute("UPDATE sessions SET notes = ? WHERE id = ?",
                   ("Дълъг текст " * 40, session_ids(path)[0]))
    res = client.get("/api/me/calendar.ics", headers=coach)
    for line in res.text.split("\r\n"):
        assert len(line.encode()) <= 75


# --- events stream ---

def _read_stream(client, headers, then, wait_s=3.0):
    """Read an SSE stream in a background thread while `then()` publishes events."""
    import queue as queue_mod
    import threading as threading_mod

    lines = queue_mod.Queue()

    def reader():
        try:
            with client.stream("GET", "/api/events", headers=headers) as response:
                for line in response.iter_lines():
                    lines.put(line)
        except Exception:
            pass  # the stream is torn down when the client context closes

    thread = threading_mod.Thread(target=reader, daemon=True)
    thread.start()
    time.sleep(0.2)  # let the subscription register
    then()
    collected = []
    while True:
        try:
            collected.append(lines.get(timeout=wait_s))
        except queue_mod.Empty:
            break
    return collected


def test_events_stream_scope(api, auth):
    client, path = api
    coach2 = auth("coach2")
    athlete1 = auth("athlete1")
    # A coach of another club receives nothing for club-1 events.
    received = _read_stream(client, coach2, lambda: client.post("/api/jumps", json=METRICS, headers=athlete1))
    assert not any('"type": "jump"' in line for line in received)


def test_events_stream_delivery(api, auth):
    client, path = api
    coach = auth("coach")
    athlete1 = auth("athlete1")
    received = _read_stream(client, coach, lambda: client.post("/api/jumps", json=METRICS, headers=athlete1))
    body = "\n".join(received)
    assert '"type": "jump"' in body
    assert "knee_ankle_ratio" not in body  # no metric values in the stream


def test_events_stream_limit(api, auth):
    client, path = api
    coach = auth("coach")
    # Three open streams is allowed; a fourth is refused. Opening streams that we then
    # abandon is fine because the bus cap is enforced per live subscription.
    streams = []
    for _ in range(3):
        stream = client.stream("GET", "/api/events", headers=coach)
        stream.__enter__()
        streams.append(stream)
    with client.stream("GET", "/api/events", headers=coach) as extra:
        assert extra.status_code == 429
    for stream in streams:
        stream.__exit__(None, None, None)


def test_events_data_shape_via_bus(api, auth):
    client, path = api
    bus = main.EventBus()
    queue = bus.open(1)
    bus.publish({1}, {"type": "jump", "id": 1})
    assert queue.get_nowait() == {"type": "jump", "id": 1}
    bus.close(1, queue)
    assert bus.open_count(1) == 0


# --- security: rate limits, pruning, HSTS ---

def test_per_address_login_limit(api):
    client, path = api
    for n in range(30):
        r = client.post("/api/auth/login", json={"email": f"spray{n}@other.test", "password": "wrong"})
        assert r.status_code == 401
    # the 31st distinct-email failure from the same address trips the per-address limit
    blocked = client.post("/api/auth/login", json={"email": "spray@other.test", "password": "demo1234"})
    assert blocked.status_code == 429
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM address_failures").fetchone()[0] >= 30


def test_expired_tokens_pruned_on_login(api, auth):
    client, path = api
    with main.database(path) as db:
        db.execute("INSERT INTO tokens(token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                   (main.token_hash("expired-token"), user_id(path, "athlete1"), time.time() - 10))
    assert client.post("/api/auth/login", json={"email": "coach@demo.test", "password": "demo1234"}).status_code == 200
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM tokens WHERE token_hash = ?",
                          (main.token_hash("expired-token"),)).fetchone()[0] == 0


def test_hsts_header(api):
    client, _ = api
    assert client.get("/").headers["strict-transport-security"] == "max-age=31536000"


# --- export / delete including new data ---

def test_export_and_delete_include_checkins_and_comments(api, auth):
    client, path = api
    athlete1 = auth("athlete1")
    aid = user_id(path, "athlete1")
    coach = auth("coach")
    client.post("/api/comments", json={"athlete_id": aid, "body": "Коментар"}, headers=coach)
    export = client.get("/api/me/export", headers=athlete1).json()
    assert "checkins" in export and "comments" in export
    assert len(export["checkins"]) == 14
    assert any(c["body"] == "Коментар" for c in export["comments"])
    jumps_have_sessions = any(j.get("session_id") is not None for j in export["jumps"])
    assert jumps_have_sessions
    assert client.delete("/api/me/data", headers=athlete1).status_code == 204
    assert client.get("/api/me/export", headers=athlete1).json()["checkins"] == []
    assert client.get("/api/me/export", headers=athlete1).json()["comments"] == []


def test_csv_has_session_column(api, auth):
    client, path = api
    coach = auth("coach")
    aid = user_id(path, "athlete1")
    res = client.get(f"/api/athletes/{aid}/export.csv", headers=coach)
    rows = list(csv.DictReader(io.StringIO(res.text)))
    assert "session_id" in rows[0]
    assert any(r["session_id"] not in ("", None) for r in rows)


# --- migration ---

def test_migrates_previous_club_schema(tmp_path):
    path = tmp_path / "old.db"
    with sqlite3.connect(path) as db:
        db.executescript('''
            CREATE TABLE clubs(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
            CREATE TABLE users(id INTEGER PRIMARY KEY, club_id INTEGER NOT NULL REFERENCES clubs(id),
                email TEXT NOT NULL UNIQUE COLLATE NOCASE, name TEXT NOT NULL, role TEXT NOT NULL,
                password_salt BLOB NOT NULL, password_hash BLOB NOT NULL);
            CREATE TABLE tokens(token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id),
                expires_at REAL NOT NULL);
            CREATE TABLE login_failures(email TEXT NOT NULL, failed_at REAL NOT NULL);
            CREATE TABLE jumps(id INTEGER PRIMARY KEY, athlete_id INTEGER NOT NULL REFERENCES users(id),
                created_at TEXT NOT NULL, metrics TEXT NOT NULL);
            INSERT INTO clubs VALUES(1, 'Legacy');
        ''')
        salt = b'0123456789abcdef'
        db.execute('INSERT INTO users VALUES(1,1,?,?,?, ?,?)',
                   ('old@demo.test', 'Old', 'athlete', salt, main.password_hash('old-password', salt)))
        db.execute('INSERT INTO jumps VALUES(1,1,?,?)', ('2026-01-01T00:00:00+00:00', json.dumps(METRICS)))
    with TestClient(main.create_app(str(path))) as client:
        h = {"Authorization": "Bearer " + client.post("/api/auth/login", json={
            "email": "old@demo.test", "password": "old-password"}).json()["token"]}
        assert client.get("/api/groups", headers=h).json() == []  # athlete sees own (empty) groups
        assert client.get("/api/me/calendar.ics", headers=h).status_code == 200
    with main.database(path) as db:
        assert {"groups", "sessions", "checkins", "comments"} <= {
            row["name"] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        cols = {row["name"] for row in db.execute("PRAGMA table_info(jumps)")}
        assert {"session_id", "client_id"} <= cols


def test_seed_idempotent_club_data(api):
    client, path = api
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM groups").fetchone()[0] == 2
        assert db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 5
        assert db.execute("SELECT COUNT(*) FROM comments").fetchone()[0] == 3
        assert db.execute("SELECT COUNT(*) FROM sessions WHERE cancelled = 1").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM checkins").fetchone()[0] == 98
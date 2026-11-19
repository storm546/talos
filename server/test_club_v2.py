"""Club platform: groups, sessions, check-ins, feedback, events, iCalendar, security."""

import asyncio
from concurrent.futures import ThreadPoolExecutor
import csv
import io
import json
from pathlib import Path
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from starlette.requests import Request
from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware

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
    assert client.post("/api/jumps", json={**METRICS, "session_id": 99999}, headers=athlete1).status_code == 422
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
    future = (datetime.now(timezone.utc) + timedelta(days=2)).strftime("%Y-%m-%d")
    assert client.put(f"/api/me/checkins/{future}", json=CHECKIN, headers=athlete1).status_code == 422
    old = (datetime.now(timezone.utc) - timedelta(days=7)).strftime("%Y-%m-%d")
    assert client.put(f"/api/me/checkins/{old}", json=CHECKIN, headers=athlete1).status_code == 422
    assert client.put("/api/me/checkins/2026-13-40", json=CHECKIN, headers=athlete1).status_code == 422
    for bad in ({**CHECKIN, "sleep": 6}, {**CHECKIN, "mood": 0}, {**CHECKIN, "training_minutes": 45},
                {**CHECKIN, "rpe": 5}, {**CHECKIN, "rpe": 11}, {**CHECKIN, "extra": 1}, {}):
        assert client.put(f"/api/me/checkins/{today_str}", json=bad, headers=athlete1).status_code == 422


@pytest.mark.parametrize("offset,accepted", [(-7, False), (-6, True), (0, True), (1, True), (2, False)])
def test_checkin_local_day_window(api, auth, monkeypatch, offset, accepted):
    client, _ = api
    athlete = auth("athlete1")
    # 00:30 in Bulgaria is still the previous UTC day, in winter and summer.
    for now in (datetime(2026, 11, 17, 22, 30, tzinfo=timezone.utc),
                datetime(2026, 6, 17, 21, 30, tzinfo=timezone.utc)):
        freeze_clock(monkeypatch, now)
        day = (now.date() + timedelta(days=offset)).isoformat()
        response = client.put(f"/api/me/checkins/{day}", json=CHECKIN, headers=athlete)
        if accepted:
            assert response.status_code in (200, 201)
            assert response.json()["day"] == day
        else:
            assert response.status_code == 422


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

def event_response(client, headers):
    """Call the handler without an HTTP client buffering an unbounded response."""
    request = Request({"type": "http", "app": client.app,
                       "headers": [(b"authorization", headers["Authorization"].encode())]})
    with main.database(client.app.state.db_path) as db:
        user = main.current_user(headers["Authorization"], db)
    endpoint = next(route.endpoint for route in client.app.routes if route.path == "/api/events")
    return endpoint(request, user)


def test_events_stream_scope(api, auth):
    client, path = api
    coach2, athlete1 = auth("coach2"), auth("athlete1")

    async def check():
        response = await event_response(client, coach2)
        # Start reading so both old and new handlers have subscribed.
        read = asyncio.create_task(anext(response.body_iterator))
        await asyncio.sleep(0)
        try:
            assert client.post("/api/jumps", json=METRICS, headers=athlete1).status_code == 201
            with pytest.raises(asyncio.TimeoutError):
                await asyncio.wait_for(read, timeout=0.05)
        finally:
            read.cancel()
            await response.body_iterator.aclose()
        assert client.app.state.events.open_count(user_id(path, "coach2")) == 0

    asyncio.run(check())


def test_events_stream_delivery(api, auth):
    client, path = api
    coach, athlete1 = auth("coach"), auth("athlete1")

    async def check():
        response = await event_response(client, coach)
        read = asyncio.create_task(anext(response.body_iterator))
        await asyncio.sleep(0)
        try:
            assert client.post("/api/jumps", json=METRICS, headers=athlete1).status_code == 201
            body = await asyncio.wait_for(read, timeout=1)
            assert '"type": "jump"' in body
            assert "knee_ankle_ratio" not in body
        finally:
            await response.body_iterator.aclose()
        assert client.app.state.events.open_count(user_id(path, "coach")) == 0

    asyncio.run(check())


def test_events_stream_limit_reserved_before_response(api, auth):
    client, path = api
    coach = auth("coach")
    uid = user_id(path, "coach")

    async def check():
        # No response body has started: concurrent handlers must reserve all slots here.
        responses = await asyncio.gather(*(event_response(client, coach) for _ in range(10)),
                                         return_exceptions=True)
        accepted = [r for r in responses if not isinstance(r, Exception)]
        refused = [r for r in responses if isinstance(r, HTTPException)]
        try:
            assert len(accepted) == 3
            assert len(refused) == 7 and all(r.status_code == 429 for r in refused)
            assert client.app.state.events.open_count(uid) == 3
        finally:
            for response in accepted:
                read = asyncio.create_task(anext(response.body_iterator))
                await asyncio.sleep(0)
                client.app.state.events.publish({uid}, {"type": "session", "id": 1})
                await asyncio.wait_for(read, timeout=1)
                await response.body_iterator.aclose()
        assert client.app.state.events.open_count(uid) == 0
        response = await event_response(client, coach)
        client.app.state.events.publish({uid}, {"type": "session", "id": 2})
        disconnected = asyncio.Event()
        messages = []

        async def receive():
            await disconnected.wait()
            return {"type": "http.disconnect"}

        async def send(message):
            messages.append(message)
            if message["type"] == "http.response.body":
                disconnected.set()

        await asyncio.wait_for(response({"type": "http", "asgi": {"spec_version": "2.0"}},
                                        receive, send), timeout=1)
        assert messages[0]["status"] == 200
        assert client.app.state.events.open_count(uid) == 0

    asyncio.run(check())


def test_events_data_shape_via_bus(api, auth):
    bus = main.EventBus()
    queue = bus.try_open(1, 3)
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


# --- independent review regressions ---

@pytest.mark.parametrize("grouped", [False, True])
def test_review_session_report_contains_only_own_row(api, auth, grouped):
    client, path = api
    coach, athlete = auth("coach"), auth("athlete1")
    sid = client.post("/api/sessions", headers=coach, json=session_body(
        group_id=group_id(path) if grouped else None)).json()["id"]
    assert client.post("/api/jumps", headers=athlete,
                       json={**METRICS, "session_id": sid}).status_code == 201
    staff = client.get(f"/api/sessions/{sid}/report", headers=coach).json()["athletes"]
    mine = client.get(f"/api/sessions/{sid}/report", headers=athlete).json()["athletes"]
    assert len(staff) > 1
    assert mine == [next(row for row in staff if row["athlete_id"] == user_id(path, "athlete1"))]


def test_review_successful_login_preserves_address_failures(api):
    client, path = api
    for n in range(29):
        assert client.post("/api/auth/login", json={
            "email": f"guess{n}@other.test", "password": "wrong"}).status_code == 401
    assert client.post("/api/auth/login", json={
        "email": "athlete1@demo.test", "password": "demo1234"}).status_code == 200
    assert client.post("/api/auth/login", json={
        "email": "last@other.test", "password": "wrong"}).status_code == 401
    blocked = client.post("/api/auth/login", json={
        "email": "new@other.test", "password": "wrong"})
    assert blocked.status_code == 429
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM address_failures").fetchone()[0] == 30


def test_review_proxy_startup_and_address_trust(api):
    root = Path(__file__).resolve().parents[1]
    dockerfile = (root / "Dockerfile").read_text()
    compose = (root / "docker-compose.yml").read_text()
    service = (root / "deploy/jumpscan.service").read_text()
    readme = (root / "deploy/README.md").read_text()
    for startup in (dockerfile, service):
        assert "--proxy-headers" in startup
        assert "--forwarded-allow-ips" in startup and "${FORWARDED_ALLOW_IPS" in startup
    assert "FORWARDED_ALLOW_IPS:" in compose
    assert "FORWARDED_ALLOW_IPS=127.0.0.1" in readme
    assert "bridge gateway" in readme
    # Uvicorn supplies request.client; only its configured trusted peer may forward it.
    _, path = api
    for peer, forwarded, expected in (("10.0.0.2", "198.51.100.1", "198.51.100.1"),
                                      ("10.0.0.9", "198.51.100.2", "10.0.0.9")):
        app = ProxyHeadersMiddleware(main.create_app(path), trusted_hosts=["10.0.0.2"])
        with TestClient(app, client=(peer, 1234)) as client:
            assert client.post("/api/auth/login", headers={"X-Forwarded-For": forwarded}, json={
                "email": "proxy-test@other.test", "password": "wrong"}).status_code == 401
        with main.database(path) as db:
            assert db.execute("SELECT address FROM address_failures ORDER BY rowid DESC LIMIT 1").fetchone()[0] == expected


@pytest.mark.parametrize("starts_at", ["2026-12-01", "2026-12-01T10:00:00",
                                       "1999-12-31T23:00:00Z", "2101-01-01T00:00:00Z",
                                       "9999-12-31T23:59:59-05:00"])
def test_review_session_rejects_unsafe_timestamp(api, auth, starts_at):
    client, path = api
    coach = auth("coach")
    assert client.post("/api/sessions", json=session_body(starts_at=starts_at), headers=coach).status_code == 422
    sid = session_ids(path)[0]
    assert client.patch(f"/api/sessions/{sid}", json=session_body(starts_at=starts_at), headers=coach).status_code == 422
    assert client.get("/api/me/calendar.ics", headers=coach).status_code == 200


def test_review_session_utc_storage_and_order(api, auth):
    client, path = api
    coach = auth("coach")
    early = client.post("/api/sessions", headers=coach, json=session_body(
        starts_at="2026-12-01T10:00:00+02:00")).json()
    late = client.post("/api/sessions", headers=coach, json=session_body(
        starts_at="2026-12-01T09:00:00Z")).json()
    assert early["starts_at"] == "2026-12-01T08:00:00+00:00"
    assert late["starts_at"] == "2026-12-01T09:00:00+00:00"
    rows = client.get("/api/sessions", headers=coach, params={
        "from": "2026-12-01T09:30:00+02:00", "to": "2026-12-01T11:30:00+02:00"}).json()
    assert [r["id"] for r in rows] == [early["id"], late["id"]]
    edited = client.patch(f'/api/sessions/{late["id"]}', headers=coach,
                          json=session_body(starts_at="2026-12-01T11:00:00+03:00"))
    assert edited.json()["starts_at"] == "2026-12-01T08:00:00+00:00"
    for value in ("2000-01-01T00:00:00Z", "2100-12-31T23:59:59Z"):
        assert client.post("/api/sessions", headers=coach, json=session_body(starts_at=value)).status_code == 201
    # A valid input year may cross a UTC year boundary during normalization.
    edge = client.post("/api/sessions", headers=coach, json=session_body(
        starts_at="2100-12-31T23:59:59-01:00")).json()
    assert edge["starts_at"] == "2101-01-01T00:59:59+00:00"
    assert f'UID:session-{edge["id"]}@' in client.get("/api/me/calendar.ics", headers=coach).text


def freeze_clock(monkeypatch, at):
    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return at.astimezone(tz) if tz else at.replace(tzinfo=None)

    monkeypatch.setattr(main, "datetime", Clock)


def test_review_calendar_legacy_timestamps_and_thirty_day_filter(api, auth, monkeypatch):
    client, path = api
    coach = auth("coach")
    freeze_clock(monkeypatch, datetime(2026, 11, 18, 12, tzinfo=timezone.utc))
    ids = session_ids(path)
    # Legacy strings cross the UTC cutoff in opposite directions to lexical ordering.
    with main.database(path) as db:
        db.execute("UPDATE sessions SET starts_at = ? WHERE id = ?", ("2026-10-19T13:00:00+02:00", ids[0]))
        db.execute("UPDATE sessions SET starts_at = ? WHERE id = ?", ("2026-10-19T11:00:00-02:00", ids[1]))
        db.execute("UPDATE sessions SET starts_at = ? WHERE id = ?", ("9999-12-31T23:59:59-05:00", ids[2]))
    res = client.get("/api/me/calendar.ics", headers=coach)
    assert res.status_code == 200
    assert f"UID:session-{ids[0]}@" not in res.text
    assert f"UID:session-{ids[1]}@" in res.text
    assert f"UID:session-{ids[2]}@" not in res.text


def test_review_comment_audit_commits_without_body(api, auth, monkeypatch):
    client, path = api
    coach = auth("coach")
    aid, actor = user_id(path, "athlete1"), user_id(path, "coach")
    response = client.post("/api/comments", headers=coach,
                           json={"athlete_id": aid, "body": "Private feedback"})
    assert response.status_code == 201
    with main.database(path) as db:
        row = db.execute("SELECT * FROM audit WHERE action = 'comment_created' AND target = ?",
                         (str(response.json()["id"]),)).fetchone()
        assert row is not None and row["actor_id"] == actor
        assert json.loads(row["detail"]) == {}
        count = db.execute("SELECT COUNT(*) FROM comments").fetchone()[0]

    def fail_audit(*args, **kwargs):
        raise RuntimeError("audit unavailable")

    monkeypatch.setattr(main, "audit", fail_audit)
    assert client.post("/api/comments", headers=coach,
                       json={"athlete_id": aid, "body": "Must roll back"}).status_code == 500
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM comments").fetchone()[0] == count


def test_review_startup_adds_partial_unique_jump_index(api):
    _, path = api
    aid, other = user_id(path, "athlete1"), user_id(path, "athlete2")
    # Simulate a database already migrated by the reviewed commit.
    with main.database(path) as db:
        db.execute("DROP INDEX IF EXISTS jumps_client")
        before = db.execute("SELECT COUNT(*) FROM jumps").fetchone()[0]
    main.init_db(path)
    main.init_db(path)
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM jumps").fetchone()[0] == before
        index = db.execute("SELECT sql FROM sqlite_master WHERE name = 'jumps_client'").fetchone()
        assert index is not None and "WHERE client_id IS NOT NULL" in index[0]
        def insert(athlete, cid):
            db.execute("INSERT INTO jumps(athlete_id, created_at, metrics, client_id) VALUES (?, ?, ?, ?)",
                       (athlete, main.now_iso(), json.dumps(METRICS), cid))
        insert(aid, "unique")
        with pytest.raises(sqlite3.IntegrityError):
            insert(aid, "unique")
        insert(other, "unique")
        insert(aid, None)
        insert(aid, None)


def test_review_upload_conflict_returns_original(api, auth):
    client, path = api
    headers = auth("athlete1")
    body = {**METRICS, "client_id": "racing-upload"}
    ready = threading.Barrier(2)

    def upload():
        ready.wait(timeout=5)
        return client.post("/api/jumps", headers=headers, json=body)

    with ThreadPoolExecutor(max_workers=2) as executor:
        requests = [executor.submit(upload) for _ in range(2)]
        responses = [request.result(timeout=10) for request in requests]
    assert sorted(response.status_code for response in responses) == [200, 201]
    assert responses[0].json() == responses[1].json()
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM jumps WHERE client_id = 'racing-upload'").fetchone()[0] == 1


def test_review_upload_retry_after_cancellation(api, auth):
    client, path = api
    coach, athlete = auth("coach"), auth("athlete1")
    session = client.post("/api/sessions", headers=coach, json=session_body()).json()
    body = {**METRICS, "session_id": session["id"], "client_id": "cancelled-retry"}
    first = client.post("/api/jumps", headers=athlete, json=body)
    assert first.status_code == 201
    assert client.patch(f'/api/sessions/{session["id"]}', headers=coach,
                        json=session_body(cancelled=True)).status_code == 200
    repeat = client.post("/api/jumps", headers=athlete, json=body)
    assert repeat.status_code == 200 and repeat.json() == first.json()


def test_review_checkin_canonical_day(api, auth, monkeypatch):
    client, path = api
    athlete = auth("athlete1")
    freeze_clock(monkeypatch, datetime(2026, 11, 5, 12, tzinfo=timezone.utc))
    aid = user_id(path, "athlete1")
    with main.database(path) as db:
        db.execute("DELETE FROM checkins WHERE athlete_id = ?", (aid,))
    first = client.put("/api/me/checkins/2026-11-5", headers=athlete, json=CHECKIN)
    assert first.status_code == 201 and first.json()["day"] == "2026-11-05"
    repeat = client.put("/api/me/checkins/2026-11-05", headers=athlete, json={**CHECKIN, "sleep": 3})
    assert repeat.status_code == 200
    rows = client.get("/api/checkins", headers=athlete).json()
    assert len(rows) == 1 and rows[0]["day"] == "2026-11-05" and rows[0]["sleep"] == 3


def test_review_concurrent_first_checkin(api, auth):
    client, path = api
    athlete = auth("athlete1")
    aid = user_id(path, "athlete1")
    day = today()
    with main.database(path) as db:
        db.execute("DELETE FROM checkins WHERE athlete_id = ? AND day = ?", (aid, day))
    barrier = threading.Barrier(2)

    def racing_db():
        with main.database(path) as db:
            class Connection:
                def execute(self, sql, params=()):
                    cursor = db.execute(sql, params)
                    if sql.startswith("SELECT * FROM checkins WHERE athlete_id") and not db.in_transaction:
                        # Synchronize unprotected reads; BEGIN IMMEDIATE correctly serializes them.
                        barrier.wait(timeout=2)
                    return cursor

                def __getattr__(self, name):
                    return getattr(db, name)
            yield Connection()

    client.app.dependency_overrides[main.get_db] = racing_db
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(lambda _: client.put(
                f"/api/me/checkins/{day}", headers=athlete, json=CHECKIN), range(2)))
    finally:
        client.app.dependency_overrides.clear()
    assert sorted(r.status_code for r in responses) == [200, 201]
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM checkins WHERE athlete_id = ? AND day = ?", (aid, day)).fetchone()[0] == 1


@pytest.mark.parametrize("invalidate", ["logout", "password", "expiry", "deactivate", "inactive"])
def test_review_event_stream_ends_when_auth_invalid(api, auth, monkeypatch, invalidate):
    client, path = api
    athlete, coach = auth("athlete1"), auth("coach")
    aid = user_id(path, "athlete1")
    real_wait_for = asyncio.wait_for

    async def fast_tick(awaitable, timeout):
        return await real_wait_for(awaitable, 0.01 if timeout == 25 else timeout)

    monkeypatch.setattr(main.asyncio, "wait_for", fast_tick)

    async def check():
        response = await event_response(client, athlete)
        try:
            assert await anext(response.body_iterator) == ": keep-alive\n\n"
            if invalidate == "logout":
                assert client.post("/api/auth/logout", headers=athlete).status_code == 204
            elif invalidate == "password":
                assert client.post(f"/api/users/{aid}/password", headers=coach,
                                   json={"password": "new-password"}).status_code == 204
            elif invalidate == "deactivate":
                assert client.patch(f"/api/users/{aid}", headers=coach, json={"active": False}).status_code == 200
            else:
                with main.database(path) as db:
                    if invalidate == "expiry":
                        db.execute("UPDATE tokens SET expires_at = ? WHERE user_id = ?", (time.time() - 1, aid))
                    else:
                        db.execute("UPDATE users SET active = 0 WHERE id = ?", (aid,))
            with pytest.raises(StopAsyncIteration):
                await real_wait_for(anext(response.body_iterator), timeout=1)
        finally:
            await response.body_iterator.aclose()
        assert client.app.state.events.open_count(aid) == 0

    asyncio.run(check())


def test_review_below_usual_uses_calendar_days(api, auth):
    client, path = api
    coach = auth("coach")
    aid = user_id(path, "athlete1")
    day = datetime.now(timezone.utc).date()
    with main.database(path) as db:
        db.execute("DELETE FROM checkins WHERE athlete_id = ?", (aid,))
        for age in (0, 1, 2, 3, 14, 15, 30, 40, 50, 60, 70):
            value = 2 if age == 0 else 3 if age <= 14 else 5
            db.execute("INSERT INTO checkins(athlete_id, day, sleep, fatigue, soreness, stress, mood, "
                       "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                       (aid, (day - timedelta(days=age)).isoformat(), *([value] * 5), main.now_iso(), main.now_iso()))
    row = next(r for r in client.get("/api/checkins/team", headers=coach).json() if r["athlete_id"] == aid)
    assert row["below_usual"] is None  # Only four check-ins in the previous 14 calendar days.
    with main.database(path) as db:
        db.execute("UPDATE checkins SET day = ? WHERE athlete_id = ? AND day = ?",
                   ((day - timedelta(days=4)).isoformat(), aid, (day - timedelta(days=15)).isoformat()))
    row = next(r for r in client.get("/api/checkins/team", headers=coach).json() if r["athlete_id"] == aid)
    assert row["below_usual"] is True  # Day 14 is included, and today is excluded.


def test_review_calendar_strips_controls_before_escaping(api, auth):
    client, path = api
    coach = auth("coach")
    text = "A\x00\x0b\x0c\x1f\x7f\x85\u2028\u2029B,;\\\r\nC"
    created = client.post("/api/sessions", headers=coach, json=session_body(
        title=text, place=text, notes=text))
    assert created.status_code == 201
    res = client.get("/api/me/calendar.ics", headers=coach)
    unfolded = res.text.replace("\r\n ", "")
    for prefix in ("SUMMARY:", "LOCATION:", "DESCRIPTION:"):
        assert prefix + "AB\\,\\;\\\\\\nC\r\n" in unfolded
    assert not any(c in res.text for c in "\x00\x0b\x0c\x1f\x7f\x85\u2028\u2029")


def test_review_admin_session_group_must_match_club(api, auth):
    client, path = api
    admin = auth("admin")
    other = client.post("/api/groups", headers=admin,
                        json={"name": "Other club", "club_id": club2_id(path)}).json()
    assert client.post("/api/sessions", headers=admin,
                       json=session_body(group_id=other["id"])).status_code == 422
    assert client.post("/api/sessions", headers=admin,
                       json=session_body(group_id=group_id(path))).status_code == 201


@pytest.mark.parametrize("session", ["missing", "other_group", "other_club"])
def test_review_upload_missing_or_invisible_session_is_422(api, auth, session):
    client, path = api
    athlete = auth("athlete1")
    if session == "missing":
        sid = 99999
    else:
        coach = auth("coach2" if session == "other_club" else "coach")
        group = group_id(path, "Състезатели") if session == "other_group" else None
        sid = client.post("/api/sessions", headers=coach, json=session_body(group_id=group)).json()["id"]
    assert client.post("/api/jumps", headers=athlete, json={**METRICS, "session_id": sid}).status_code == 422


def test_review_event_proxy_buffering_disabled(api, auth):
    client, path = api
    coach = auth("coach")

    async def check():
        response = await event_response(client, coach)
        read = asyncio.create_task(anext(response.body_iterator))
        await asyncio.sleep(0)
        try:
            assert response.headers.get("x-accel-buffering") == "no"
        finally:
            read.cancel()
            with pytest.raises(asyncio.CancelledError):
                await read
            await response.body_iterator.aclose()

    asyncio.run(check())
    readme = (Path(__file__).resolve().parents[1] / "deploy/README.md").read_text()
    location = readme.split("location = /api/events {", 1)[1].split("}", 1)[0]
    assert "proxy_buffering off;" in location and "proxy_pass" in location

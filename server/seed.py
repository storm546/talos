"""Create synthetic demo accounts, jumps and club data, once per database."""

from datetime import datetime, timedelta, timezone
import random
import secrets

from .main import Checkin, Jump, database, database_path, init_db, password_hash


def seed(path=None):
    path = database_path() if path is None else path
    init_db(path)
    with database(path) as db:
        db.execute("BEGIN IMMEDIATE")
        athlete_ids = {}
        user_ids = {}
        demo_club_id = None
        for club_name, accounts in (
            ("Демо клуб", (("admin", "Демо администратор", "admin"), ("coach", "Демо треньор", "coach"),
                           ("athlete1", "Александра Иванова", "athlete"), ("athlete2", "Борис Петров", "athlete"),
                           ("athlete4", "Виктория Георгиева", "athlete"), ("athlete5", "Даниел Стоянов", "athlete"),
                           ("athlete6", "Елена Димитрова", "athlete"), ("athlete7", "Мартин Колев", "athlete"))),
            ("Втори демо клуб", (("coach2", "Втори демо треньор", "coach"), ("athlete3", "Иван Николов", "athlete"))),
        ):
            db.execute("INSERT OR IGNORE INTO clubs(name) VALUES (?)", (club_name,))
            club_id = db.execute("SELECT id FROM clubs WHERE name = ?", (club_name,)).fetchone()[0]
            if club_name == "Демо клуб":
                demo_club_id = club_id
            for account, name, role in accounts:
                email = account + "@demo.test"
                existing = db.execute("SELECT id FROM users WHERE email = ?", (email,)).fetchone()
                if existing:
                    user_ids[account] = existing[0]
                    if role == "athlete":
                        athlete_ids[account] = existing[0]
                    continue
                salt = secrets.token_bytes(16)
                cursor = db.execute(
                    "INSERT INTO users(club_id, email, name, role, password_salt, password_hash, "
                    "consent_recorded, consent_by, consent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (club_id, email, name, role, salt, password_hash("demo1234", salt), role == "athlete",
                     "Synthetic demo consent" if role == "athlete" else None,
                     datetime.now(timezone.utc).isoformat() if role == "athlete" else None),
                )
                user_ids[account] = cursor.lastrowid
                if role == "athlete":
                    athlete_ids[account] = cursor.lastrowid
                if role != "athlete":
                    continue
                if db.execute("SELECT COUNT(*) FROM jumps WHERE athlete_id = ?",
                              (cursor.lastrowid,)).fetchone()[0]:
                    continue
                variant = int(account[-1])
                noise = random.Random(variant).gauss  # same demo data on every run, but not a ruler-straight line
                for index in range(10):
                    left = 62 + variant * 2 + index * (1.3 if variant == 1 else .4) + noise(0, 2.5)
                    right = left + (1 + index * 1.2 if variant == 2 else 2 + noise(0, 1.5))
                    frames = round((340 + variant * 8 + index * (7 if variant == 1 else 2) + noise(0, 12)) * .06)
                    flight_ms = frames * 1000 / 60  # flight time comes in whole 60 fps frames
                    metrics = Jump(
                        test="cmj" if index % 2 else "drop_jump", fps=60, flight_time_ms=flight_ms,
                        jump_height_cm=9.80665 * (flight_ms / 1000) ** 2 / 8 * 100,
                        knee_flexion_contact_l=20 + index / 2 + noise(0, 2), knee_flexion_contact_r=22 + index / 2 + noise(0, 2),
                        knee_flexion_peak_l=left, knee_flexion_peak_r=right, fppa_peak_l=5 + noise(0, 2), fppa_peak_r=6 + noise(0, 2),
                        knee_ankle_ratio=.81 + variant * .02 + index * (.018 if variant == 1 else .003) + noise(0, .02),
                        flexion_asymmetry_pct=abs(left - right) / max(left, right) * 100,
                        quality=.78 if index == 6 else .95, warnings=["low_visibility"] if index == 6 else [],
                    )
                    created_at = (datetime.now(timezone.utc) - timedelta(days=(10 - index) * 4)).isoformat()
                    db.execute("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                               (cursor.lastrowid, created_at, metrics.model_dump_json()))

        # Club platform seed: two groups, five sessions, check-ins and comments.
        if not db.execute("SELECT COUNT(*) FROM groups").fetchone()[0]:
            groups = {}
            for group_name, members in (
                ("Юноши", ("athlete1", "athlete2", "athlete4")),
                ("Състезатели", ("athlete5", "athlete6", "athlete7")),
            ):
                cursor = db.execute("INSERT INTO groups(club_id, name) VALUES (?, ?)",
                                    (demo_club_id, group_name))
                groups[group_name] = cursor.lastrowid
                for account in members:
                    db.execute("INSERT INTO group_members(group_id, user_id) VALUES (?, ?)",
                               (cursor.lastrowid, athlete_ids[account]))
        else:
            groups = {row["name"]: row["id"] for row in
                      db.execute("SELECT * FROM groups WHERE club_id = ?", (demo_club_id,))}

        if not db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]:
            now = datetime.now(timezone.utc)
            sessions = (
                # title, days offset from now, group, test, trials, place, cancelled
                ("Тренировка скокове", -20, "Юноши", "drop_jump", 3, "Зала 1", False),
                ("Тест срещу часовник", -10, None, "cmj", 3, "Зала 2", False),
                ("Седмичен тест", -3, "Състезатели", "drop_jump", 3, "Зала 1", False),
                ("Контролно тестване", 2, None, "cmj", 3, "Зала 2", False),
                ("Състезателна подготовка", 7, "Състезатели", "drop_jump", 3, "Зала 1", True),
            )
            session_ids = []
            for title, offset, group_name, test, trials, place, cancelled in sessions:
                starts_at = (now + timedelta(days=offset)).isoformat()
                cursor = db.execute(
                    "INSERT INTO sessions(club_id, group_id, title, starts_at, place, test, trials, "
                    "notes, created_by, cancelled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (demo_club_id, groups.get(group_name), title, starts_at, place, test, trials,
                     "Демо сесия — синтетични данни", user_ids.get("coach"), cancelled))
                session_ids.append((cursor.lastrowid, group_name, test))
            # Attach existing demo jumps to the three past sessions, three per athlete where possible.
            for session_id, group_name, test in session_ids[:3]:
                members = ["athlete1", "athlete2", "athlete4", "athlete5", "athlete6", "athlete7"] \
                    if group_name is None else (
                        ["athlete1", "athlete2", "athlete4"] if group_name == "Юноши"
                        else ["athlete5", "athlete6", "athlete7"])
                for account in members:
                    athlete_id = athlete_ids[account]
                    candidates = [row["id"] for row in db.execute(
                        "SELECT id FROM jumps WHERE athlete_id = ? AND session_id IS NULL ORDER BY id LIMIT 3",
                        (athlete_id,))]
                    for jump_id in candidates:
                        db.execute("UPDATE jumps SET session_id = ? WHERE id = ?", (session_id, jump_id))

        # 14 days of check-ins for every demo athlete, with one athlete clearly low today.
        # Wellbeing is stable per athlete (so the previous-14-day median is well defined);
        # training load varies day to day. athlete2 drops clearly below their usual today.
        rng = random.Random(20261118)
        if not db.execute("SELECT COUNT(*) FROM checkins").fetchone()[0]:
            today = datetime.now(timezone.utc).date()
            for account in athlete_ids:
                athlete_id = athlete_ids[account]
                variant = int(account[-1])
                base = 4 if variant % 2 == 0 else 3
                for days_ago in range(14):
                    day = today - timedelta(days=days_ago)
                    if account == "athlete2" and days_ago == 0:
                        mood, sleep, fatigue, soreness, stress = 1, 1, 1, 1, 1
                    else:
                        mood = sleep = fatigue = soreness = stress = base
                    training = rng.choice([None, (rng.randint(30, 90), rng.randint(2, 8))])
                    db.execute(
                        "INSERT INTO checkins(athlete_id, day, sleep, fatigue, soreness, stress, mood, "
                        "training_minutes, rpe, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (athlete_id, day.isoformat(), sleep, fatigue, soreness, stress, mood,
                         training[0] if training else None, training[1] if training else None,
                         now_isos(day), now_isos(day)))

        # A few coach comments and one athlete reply on the first athlete's thread.
        if not db.execute("SELECT COUNT(*) FROM comments").fetchone()[0]:
            coach_id = user_ids.get("coach")
            athlete1 = athlete_ids.get("athlete1")
            now = datetime.now(timezone.utc)
            comment = db.execute(
                "INSERT INTO comments(athlete_id, author_id, body, created_at) VALUES (?, ?, ?, ?)",
                (athlete1, coach_id, "Кацането изглежда стабилно — нека обсъдим числата на следващата тренировка.",
                 (now - timedelta(days=3)).isoformat()))
            db.execute("INSERT INTO comments(athlete_id, author_id, body, created_at) VALUES (?, ?, ?, ?)",
                       (athlete1, coach_id, "Пробвай да задържиш коленете по-меки при контакт.",
                        (now - timedelta(days=2)).isoformat()))
            db.execute("INSERT INTO comments(athlete_id, author_id, body, created_at) VALUES (?, ?, ?, ?)",
                       (athlete1, athlete1, "Ще обърна внимание, благодаря!", (now - timedelta(days=1, hours=2)).isoformat()))

        db.commit()


def now_isos(day):
    return datetime(day.year, day.month, day.day, 20, 0, 0, tzinfo=timezone.utc).isoformat()


if __name__ == "__main__":
    seed()
    print("Synthetic demo data is ready. Demo password: demo1234")
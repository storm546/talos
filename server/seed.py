"""Create or reset the fixed synthetic demo accounts and their data."""

import argparse
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
import hashlib
import json
import random
import secrets
from statistics import median

from .main import FormSet, Jump, database, database_path, init_db, password_hash
from .readiness_api import copy_checkin_load
from .training_nutrition_api import recipe_servings

SOFIA = ZoneInfo("Europe/Sofia")
DEMO_CLUBS = (
    ("Демо клуб", (("admin", "Демо администратор", "admin"), ("coach", "Демо треньор", "coach"),
                 ("athlete1", "Александра Иванова", "athlete"), ("athlete2", "Борис Петров", "athlete"),
                 ("athlete4", "Виктория Георгиева", "athlete"), ("athlete5", "Даниел Стоянов", "athlete"),
                 ("athlete6", "Елена Димитрова", "athlete"), ("athlete7", "Мартин Колев", "athlete"))),
    ("Втори демо клуб", (("coach2", "Втори демо треньор", "coach"), ("athlete3", "Иван Николов", "athlete"))),
)
DEMO_GROUPS = {"Юноши": ("athlete1", "athlete2", "athlete4"), "Състезатели": ("athlete5", "athlete6", "athlete7")}
LEADERBOARD_OPT_IN = ("athlete1", "athlete5")  # adults; the others stay opted out (SPEC 8)
SESSION_NOTE = "Демо сесия — синтетични данни"
PLAN_NOTE = "Демо план: синтетични данни"


def clear_demo_rows(db, users, athletes, club_ids):
    """Keep account IDs, audit history, and shared records used by non-demo accounts."""
    user_marks = ",".join("?" for _ in users)
    athlete_marks = ",".join("?" for _ in athletes)
    if db.execute(f"SELECT 1 FROM comments JOIN jumps ON jumps.id = comments.jump_id "
                  f"WHERE (comments.athlete_id NOT IN ({athlete_marks}) OR comments.author_id NOT IN ({user_marks})) "
                  f"AND jumps.athlete_id IN ({athlete_marks})", (*athletes, *users, *athletes)).fetchone():
        raise ValueError("A non-demo comment references a demo jump; no demo data was reset.")
    db.execute(f"DELETE FROM comments WHERE athlete_id IN ({athlete_marks}) AND author_id IN ({user_marks})", (*athletes, *users))
    for table in ("jumps", "form_sets", "checkins", "training_sessions", "set_logs", "workouts", "nutrition_day_complete",
                  "food_logs", "weights", "nutrition_targets", "coaching_programs"):
        db.execute(f"DELETE FROM {table} WHERE athlete_id IN ({athlete_marks})", athletes)
    for table in ("tokens", "group_members", "assistant_proposals", "assistant_requests", "assistant_conversations"):
        db.execute(f"DELETE FROM {table} WHERE user_id IN ({user_marks})", users)
    db.execute(f"DELETE FROM login_failures WHERE email IN (SELECT email FROM users WHERE id IN ({user_marks}))", users)
    db.execute(f"DELETE FROM sessions WHERE club_id IN (?, ?) AND created_by IN ({user_marks}) "
               "AND NOT EXISTS (SELECT 1 FROM group_members WHERE group_id = sessions.group_id) "
               "AND NOT EXISTS (SELECT 1 FROM jumps WHERE session_id = sessions.id) "
               "AND NOT EXISTS (SELECT 1 FROM comments WHERE session_id = sessions.id)", (*club_ids, *users))
    db.execute(f"DELETE FROM plans WHERE club_id IN (?, ?) AND created_by IN ({user_marks}) "
               f"AND (athlete_id IS NULL OR athlete_id IN ({athlete_marks})) "
               "AND NOT EXISTS (SELECT 1 FROM group_members WHERE group_id = plans.group_id) "
               "AND NOT EXISTS (SELECT 1 FROM set_logs WHERE plan_id = plans.id)", (*club_ids, *users, *athletes))
    db.execute(f"DELETE FROM recipes WHERE owner_id IN ({user_marks})", users)
    db.execute(f"DELETE FROM workout_templates WHERE owner_id IN ({user_marks})", users)
    db.execute(f"DELETE FROM foods WHERE owner_id IN ({user_marks}) "
               "AND NOT EXISTS (SELECT 1 FROM food_logs WHERE food_id = foods.id) "
               "AND NOT EXISTS (SELECT 1 FROM recipe_items WHERE food_id = foods.id) "
               "AND NOT EXISTS (SELECT 1 FROM recipes WHERE food_id = foods.id)", users)


def seed(path=None, *, reset=False, readiness=True):
    path = database_path() if path is None else path
    init_db(path)
    now = datetime.now(timezone.utc)
    today = now.date()
    with database(path) as db:
        db.execute("BEGIN IMMEDIATE")
        athlete_ids = {}
        user_ids = {}
        club_ids = []
        new_athletes = set()
        for club_name, accounts in DEMO_CLUBS:
            db.execute("INSERT OR IGNORE INTO clubs(name) VALUES (?)", (club_name,))
            club_id = db.execute("SELECT id FROM clubs WHERE name = ?", (club_name,)).fetchone()[0]
            club_ids.append(club_id)
            for account, name, role in accounts:
                email = account + "@demo.test"
                existing = db.execute("SELECT id FROM users WHERE email = ?", (email,)).fetchone()
                if existing:
                    user_ids[account] = existing[0]
                    if role == "athlete":
                        athlete_ids[account] = existing[0]
                    if not reset:
                        continue
                salt = secrets.token_bytes(16)
                cursor = db.execute(
                    "INSERT INTO users(club_id, email, name, role, password_salt, password_hash, "
                    "consent_recorded, consent_by, consent_at, under_18, leaderboard_opt_in) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(email) DO UPDATE SET club_id=excluded.club_id, name=excluded.name, role=excluded.role, "
                    "password_salt=excluded.password_salt, password_hash=excluded.password_hash, active=1, "
                    "consent_recorded=excluded.consent_recorded, consent_by=excluded.consent_by, "
                    "consent_at=excluded.consent_at, under_18=excluded.under_18, "
                    "leaderboard_opt_in=excluded.leaderboard_opt_in RETURNING id",
                    (club_id, email, name, role, salt, password_hash("demo1234", salt), role == "athlete",
                     "Синтетично съгласие за демонстрацията" if role == "athlete" else None,
                     now.isoformat() if role == "athlete" else None, account == "athlete7",
                     account in LEADERBOARD_OPT_IN),
                )
                user_id = cursor.fetchone()[0]
                user_ids[account] = user_id
                if role == "athlete":
                    athlete_ids[account] = user_id
                    new_athletes.add(account)

        if reset:
            clear_demo_rows(db, tuple(user_ids.values()), tuple(athlete_ids.values()), club_ids)
        demo_club_id = club_ids[0]
        for account in sorted(new_athletes):
            variant = int(account[-1])
            rng = random.Random(variant)
            noise = rng.gauss
            for index, days_ago in enumerate((32, 28, 25, 21, 17, 13, 10, 8, 4, 1)):
                left = 62 + variant * 2 + index * (1.3 if variant == 1 else .4) + noise(0, 2.5)
                right = left + (1 + index * 1.2 if variant == 2 else 2 + noise(0, 1.5))
                frames = round((340 + variant * 8 + index * (7 if variant == 1 else 2) + noise(0, 12)) * .06)
                flight_ms = frames * 1000 / 60
                metrics = Jump(
                    test="cmj" if index % 2 else "drop_jump", fps=60, flight_time_ms=flight_ms,
                    jump_height_cm=9.80665 * (flight_ms / 1000) ** 2 / 8 * 100,
                    knee_flexion_contact_l=20 + index / 2 + noise(0, 2), knee_flexion_contact_r=22 + index / 2 + noise(0, 2),
                    knee_flexion_peak_l=left, knee_flexion_peak_r=right, fppa_peak_l=5 + noise(0, 2), fppa_peak_r=6 + noise(0, 2),
                    knee_ankle_ratio=.81 + variant * .02 + index * (.018 if variant == 1 else .003) + noise(0, .02),
                    flexion_asymmetry_pct=abs(left - right) / max(left, right) * 100,
                    quality=.78 if index == 6 else .95, warnings=["low_visibility"] if index == 6 else [],
                )
                created_at = (now - timedelta(days=days_ago, hours=rng.randrange(0, 6))).isoformat()
                db.execute("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                           (athlete_ids[account], created_at, metrics.model_dump_json()))
            if reset:
                seed_form(db, athlete_ids[account], now, rng)

        groups = {}
        for group_name, members in DEMO_GROUPS.items():
            db.execute("INSERT OR IGNORE INTO groups(club_id, name) VALUES (?, ?)", (demo_club_id, group_name))
            group_id = db.execute("SELECT id FROM groups WHERE club_id = ? AND name = ?", (demo_club_id, group_name)).fetchone()[0]
            groups[group_name] = group_id
            for account in members:
                if account in new_athletes:
                    db.execute("INSERT OR IGNORE INTO group_members(group_id, user_id) VALUES (?, ?)", (group_id, athlete_ids[account]))

        if reset or not db.execute("SELECT 1 FROM sessions WHERE club_id = ?", (demo_club_id,)).fetchone():
            # Local start times in Bulgarian club time, on round quarter hours, stored as UTC.
            sessions = (
                # title, days offset from today, local hour:minute, group, test, trials, place, cancelled
                ("Тренировка: скокове от платформа", -20, 17, 30, "Юноши", "drop_jump", 3, "Зала 1", False),
                ("Контролен тест — скок от място", -11, 18, 15, None, "cmj", 3, "Зала 2", False),
                ("Седмичен тест на отбора", -7, 16, 45, "Състезатели", "drop_jump", 3, "Зала 1", False),
                ("Контролен тест на скоковете", 2, 19, 0, None, "cmj", 3, "Зала 2", False),
                ("Подготовка за състезание", 7, 17, 30, "Състезатели", "drop_jump", 3, "Зала 1", True),
            )
            session_ids = []
            for title, offset, hour, minute, group_name, test, trials, place, cancelled in sessions:
                local = datetime(
                    *(now.astimezone(SOFIA).date() + timedelta(days=offset)).timetuple()[:3],
                    hour, minute, tzinfo=SOFIA)
                starts_at = local.astimezone(timezone.utc).isoformat()
                existing = db.execute("SELECT id FROM sessions WHERE club_id=? AND title=? AND starts_at=? AND notes=?",
                                      (demo_club_id, title, starts_at, SESSION_NOTE)).fetchone()
                if existing:
                    session_ids.append((existing[0], group_name, test, local.astimezone(timezone.utc)))
                    continue
                cursor = db.execute(
                    "INSERT INTO sessions(club_id, group_id, title, starts_at, place, test, trials, "
                    "notes, created_by, cancelled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (demo_club_id, groups.get(group_name), title, starts_at, place, test, trials,
                     SESSION_NOTE, user_ids["coach"], cancelled))
                session_ids.append((cursor.lastrowid, group_name, test, local.astimezone(timezone.utc)))
            for session_id, group_name, test, starts_at in session_ids[:3]:
                members = DEMO_GROUPS[group_name] if group_name else sum(DEMO_GROUPS.values(), ())
                for account in members:
                    if account not in new_athletes:
                        continue
                    candidates = [row["id"] for row in db.execute(
                        "SELECT id FROM jumps WHERE athlete_id = ? AND session_id IS NULL "
                        "AND json_extract(metrics, '$.test') = ? ORDER BY id LIMIT ?",
                        (athlete_ids[account], test, 2 if account == "athlete7" else 3))]
                    for attempt, jump_id in enumerate(candidates, 1):
                        created_at = starts_at + timedelta(minutes=4 * attempt + int(account[-1]))
                        db.execute("UPDATE jumps SET session_id = ?, created_at = ? WHERE id = ?",
                                   (session_id, created_at.isoformat(), jump_id))

        # 14 days of check-ins for every demo athlete, with one athlete clearly low today.
        # Each athlete has their own stable wellbeing level and a personal weekly training rhythm
        # (a sport-day skipping pattern plus item-level noise), so days vary naturally without
        # looking like a test fixture. athlete2 drops clearly below their own usual level today.
        rng = random.Random(20261118)
        if new_athletes:
            for account in sorted(new_athletes):
                athlete_id = athlete_ids[account]
                variant = int(account[-1])
                level = 4 if variant % 2 == 0 else 3
                # Personal training days (offset within a two-week beat), per athlete.
                train_days = {0, 1, 3, 6, 8, 10, 12} if variant % 2 == 0 else {0, 2, 3, 5, 7, 10, 12}
                for days_ago in range(14):
                    if reset and account == "athlete7" and days_ago == 0:
                        continue
                    day = today - timedelta(days=days_ago)
                    if account == "athlete2" and days_ago == 0:
                        # Below his own usual, but still a real day — not five 1s.
                        mood, sleep, fatigue, soreness, stress = 2, 3, 2, 3, 2
                    elif days_ago == 0:
                        mood, sleep, fatigue, soreness, stress = level + 1, level, level - 1, level, level
                    else:
                        mood, sleep, fatigue, soreness, stress = [max(1, min(5, level + rng.randint(-1, 1)))
                                                                  for _ in range(5)]
                    if days_ago in train_days:
                        training_minutes = rng.randint(45, 120)
                        rpe = rng.randint(4, 8)
                    else:
                        training_minutes = rpe = None
                    db.execute(
                        "INSERT INTO checkins(athlete_id, day, sleep, fatigue, soreness, stress, mood, "
                        "training_minutes, rpe, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (athlete_id, day.isoformat(), sleep, fatigue, soreness, stress, mood,
                         training_minutes, rpe, now_isos(day), now_isos(day)))

        # A few coach comments and one athlete reply on the first athlete's thread.
        if "athlete1" in new_athletes:
            coach_id = user_ids.get("coach")
            athlete1 = athlete_ids.get("athlete1")
            db.execute(
                "INSERT INTO comments(athlete_id, author_id, body, created_at) VALUES (?, ?, ?, ?)",
                (athlete1, coach_id, "Приземяването изглежда стабилно. Нека обсъдим резултатите на следващата тренировка.",
                 (now - timedelta(days=3)).isoformat()))
            db.execute("INSERT INTO comments(athlete_id, author_id, body, created_at) VALUES (?, ?, ?, ?)",
                       (athlete1, coach_id, "Опитайте да сгънете леко коленете при приземяване.",
                        (now - timedelta(days=2)).isoformat()))
            db.execute("INSERT INTO comments(athlete_id, author_id, body, created_at) VALUES (?, ?, ?, ?)",
                       (athlete1, athlete1, "Ще обърна внимание, благодаря!", (now - timedelta(days=1, hours=2)).isoformat()))

        if new_athletes:
            seed_training_nutrition(db, user_ids, athlete_ids, groups, club_ids, today, new_athletes)
            if readiness:
                seed_readiness(db, athlete_ids, today, now, new_athletes)
        copy_checkin_load(db)
        db.commit()


# Readiness profiles for the team board (SPEC 9 wave 2): today's state and personal baselines.
# account: (state today, resting HR, RMSSD ms, readiness jump cm, bedtime offset min, under-slept today)
READINESS_PROFILES = {
    "athlete1": ("good", 50, 92, 36.0, 0),
    "athlete2": ("take_care", 56, 70, 31.0, 20),
    "athlete4": ("normal", 53, 78, 33.0, -15),
    "athlete5": ("load", 48, 101, 40.0, 10),
    "athlete6": ("learning", 58, 64, 29.0, 30),
    "athlete7": ("missing", 54, 75, 30.0, 5),
    "athlete3": ("normal", 55, 72, 32.0, 0),
}


def seed_readiness(db, athlete_ids, today, now, new_athletes):
    """28 days of synthetic morning data so the board shows every state: good, normal, take care, a load
    spike, one new athlete who is still learning and one who has not checked in today."""
    rng = random.Random(20261122)
    for account, (profile, hr, rmssd, height, bed_shift) in READINESS_PROFILES.items():
        if account not in new_athletes:
            continue
        athlete_id = athlete_ids[account]
        variant = int(account[-1])
        level = 4 if variant % 2 == 0 else 3
        history = 4 if profile == "learning" else 28
        db.execute("DELETE FROM checkins WHERE athlete_id = ? AND day < ?",
                   (athlete_id, (today - timedelta(days=history - 1)).isoformat()))
        if profile == "missing":
            db.execute("DELETE FROM checkins WHERE athlete_id = ? AND day = ?", (athlete_id, today.isoformat()))
        for days_ago in range(history):
            day = today - timedelta(days=days_ago)
            if profile == "missing" and days_ago == 0:
                continue
            items = [max(1, min(5, level + rng.randint(-1, 1))) for _ in range(5)]
            minutes, rpe = (rng.randint(45, 110), rng.randint(4, 8)) if days_ago % 7 not in (2, 5) else (None, None)
            db.execute(
                "INSERT OR IGNORE INTO checkins(athlete_id, day, sleep, fatigue, soreness, stress, mood, "
                "training_minutes, rpe, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (athlete_id, day.isoformat(), *items, minutes, rpe, now_isos(day), now_isos(day)))
            resting, variability = hr + rng.gauss(0, 1.5), rmssd * rng.gauss(1, .07)
            bed = 23 * 60 + bed_shift + rng.randint(-25, 25)
            sleep_minutes = rng.randint(445, 505)
            if days_ago == 0 and profile == "good":
                resting, variability, sleep_minutes, bed = hr - 5, rmssd * 1.3, 500, 23 * 60 + bed_shift
            elif days_ago == 0 and profile == "take_care":
                resting, variability, sleep_minutes, bed = hr + 8, rmssd * .62, 330, 25 * 60
                db.execute("UPDATE checkins SET sleep = 2, fatigue = 2, soreness = 3, stress = 2, mood = 2 "
                           "WHERE athlete_id = ? AND day = ?", (athlete_id, day.isoformat()))
            elif days_ago == 0:
                resting, variability = hr, rmssd
                sleep_minutes = 360 if profile in ("normal", "load") else sleep_minutes
            wake = bed + sleep_minutes
            db.execute("UPDATE checkins SET resting_hr = ?, hrv_rmssd = ?, bedtime = ?, wake_time = ? "
                       "WHERE athlete_id = ? AND day = ?",
                       (round(resting), round(variability, 1), f"{bed // 60 % 24:02d}:{bed % 60:02d}",
                        f"{wake // 60 % 24:02d}:{wake % 60:02d}", athlete_id, day.isoformat()))
            if days_ago % 3 == 1 and profile != "learning":  # a second session on some days: gym after practice
                db.execute("INSERT INTO training_sessions(athlete_id, day, kind, minutes, rpe, source, created_at) "
                           "VALUES (?, ?, 'gym', ?, ?, 'manual', ?)",
                           (athlete_id, day.isoformat(), rng.randint(30, 60), rng.randint(5, 7), now_isos(day)))
            if profile == "load" and days_ago < 5:  # match week: a much higher load than usual
                db.execute("INSERT INTO training_sessions(athlete_id, day, kind, minutes, rpe, source, created_at) "
                           "VALUES (?, ?, 'match', 90, 9, 'manual', ?)", (athlete_id, day.isoformat(), now_isos(day)))
            # Readiness jumps on most mornings: 2-3 countermovement jumps, the best valid one counts.
            if (profile != "missing" or days_ago > 0) and (days_ago % 4 != 3 or days_ago == 0):
                morning = rng.gauss(height, .9)
                if days_ago == 0:
                    morning = {"good": height + 2.6, "take_care": height - 3.2, "load": height - 3.0}.get(profile, height)
                for trial in range(2 if days_ago % 2 else 3):
                    jump_cm = morning - abs(rng.gauss(0, .5)) * (trial > 0)
                    flight_ms = (8 * jump_cm / 100 / 9.80665) ** .5 * 1000
                    warnings = ["uncertain_timing"] if profile == "take_care" and days_ago == 0 and trial == 2 else []
                    metrics = Jump(test="readiness", fps=60, flight_time_ms=flight_ms, jump_height_cm=jump_cm,
                                   knee_flexion_contact_l=None, knee_flexion_contact_r=None,
                                   knee_flexion_peak_l=68 + rng.gauss(0, 2), knee_flexion_peak_r=None,
                                   fppa_peak_l=None, fppa_peak_r=None, knee_ankle_ratio=None,
                                   flexion_asymmetry_pct=None, quality=.93, warnings=warnings)
                    at = datetime(day.year, day.month, day.day, 5, 10 + trial * 2, tzinfo=timezone.utc)
                    db.execute("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                               (athlete_id, min(at, now - timedelta(minutes=5 - trial)).isoformat(),
                                metrics.model_dump_json()))


def seed_form(db, athlete_id, now, rng):
    for days_ago in rng.sample(range(1, 14), 3):
        reps, start = [], 0
        for index in range(1, rng.randint(4, 7) + 1):
            left, right = rng.randint(86, 111), rng.randint(88, 113)
            descent, ascent = rng.randrange(650, 1051, 50), rng.randrange(550, 951, 50)
            angle_left, angle_right = rng.randint(1, 8), rng.randint(2, 10)
            reps.append(dict(index=index, status="complete", reasons=[], start_ms=start,
                             bottom_ms=start + descent, end_ms=start + descent + ascent,
                             depth=(left + right) / 2, knee_flexion_peak_l=left, knee_flexion_peak_r=right,
                             fppa_l=angle_left, fppa_r=angle_right, hip_below_knee=None,
                             checks={"knee_flexion_peak": (left + right) / 2, "knee_alignment": max(angle_left, angle_right)},
                             flexion_asymmetry_pct=abs(left - right) / max(left, right) * 100,
                             descent_time_ms=descent, ascent_time_ms=ascent))
            start += descent + ascent + rng.randint(300, 900)
        measure = ("depth", "knee_flexion_peak_l", "knee_flexion_peak_r", "fppa_l", "fppa_r",
                   "flexion_asymmetry_pct", "descent_time_ms", "ascent_time_ms")
        summary = {"median_" + key: median(rep[key] for rep in reps) for key in measure}
        metrics = FormSet(exercise="squat", side=None, view="front", fps=30, quality=rng.uniform(.86, .98),
                          mode="reps", source=rng.choice(("live", "video")), warnings=[], repetitions=reps,
                          summary=dict(**summary, repetition_count=len(reps), partial_count=0, unclear_count=0,
                                       hip_below_knee_count=None,
                                       worst_repetition=max(reps, key=lambda rep: rep["flexion_asymmetry_pct"])["index"]))
        created_at = (now - timedelta(days=days_ago, minutes=rng.randint(0, 150))).isoformat()
        db.execute("INSERT INTO form_sets(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                   (athlete_id, created_at, metrics.model_dump_json()))


EXERCISES = (  # name, kind, lower body
    ("Клякане със щанга", "strength", 1), ("Румънска тяга", "strength", 1), ("Напади с дъмбели", "strength", 1),
    ("Лег преса", "strength", 1), ("Лежанка", "strength", 0), ("Набирания", "strength", 0),
    ("Подскоци от приклякване", "plyometric", 1), ("Скокове през бариери", "plyometric", 1),
    ("Скок от платформа", "plyometric", 1), ("Мобилност за глезени", "mobility", 0), ("Планк", "mobility", 0),
    ("Леко бягане", "conditioning", 0), ("Скачане на въже", "conditioning", 0),
)
# weekday (0 = Monday), exercise, sets, reps, reps_max, load_kg, rpe_target; two group plans share the layout
PLAN_ITEMS = (
    (0, "Клякане със щанга", 3, 6, 8, 60, 8), (0, "Румънска тяга", 3, 8, 10, 50, 8), (0, "Планк", 2, 1, 1, None, None),
    (2, "Напади с дъмбели", 3, 8, 10, 20, 8), (2, "Лежанка", 3, 6, 8, 40, 8), (2, "Набирания", 3, 5, 8, None, 8),
    (4, "Скок от платформа", 3, 5, 5, None, None), (4, "Подскоци от приклякване", 3, 6, 6, None, None),
    (4, "Мобилност за глезени", 2, 10, 10, None, None),
)
MEAL_PLAN = (  # meal, food (Bulgarian name from the food list), grams
    ("breakfast", "Овесени ядки", 80), ("breakfast", "Кисело мляко 3.6", 200), ("breakfast", "Банани", 120),
    ("lunch", "Ориз бял сварен", 220), ("lunch", "Пилешки гърди печени", 150), ("lunch", "Домати", 150),
    ("lunch", "Зехтин", 10), ("dinner", "Картофи печени", 250), ("dinner", "Пъстърва", 150),
    ("dinner", "Бял хляб", 60), ("snack", "Ябълки", 180), ("snack", "Орехи", 30),
)

# Last week of the diary (SPEC 7.1): the snack is a scanned club food and dinner a club recipe, both by serving.
SCANNED_FOOD = {"name_bg": "Протеинов бар (демо)", "brand": "Демо", "barcode": "2000000000015", "kcal": 390,
                "protein_g": 30, "carbs_g": 40, "fat_g": 12, "fibre_g": 5, "serving": ("1 бр. (50 g)", 50)}
RECIPE = ("Пъстърва на фурна с домати", 2, (("Пъстърва", 300), ("Зехтин", 6), ("Домати", 120)))
WEEK_SWAPS = {"Орехи": "scanned", "Пъстърва": "recipe"}


def seed_food_v2(db, club_id):
    """The demo club's scanned food and recipe (idempotent); returns {kind: (food id, serving label, grams)}."""
    bar = db.execute("SELECT id FROM foods WHERE club_id = ? AND owner_id IS NULL AND barcode = ?",
                     (club_id, SCANNED_FOOD["barcode"])).fetchone()
    if bar is None:
        f = SCANNED_FOOD
        bar = [db.execute(
            "INSERT INTO foods(club_id, name_bg, brand, barcode, kcal, protein_g, carbs_g, fat_g, fibre_g, servings, "
            "nutrients, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'custom')",
            (club_id, f["name_bg"], f["brand"], f["barcode"], f["kcal"], f["protein_g"], f["carbs_g"], f["fat_g"],
             f["fibre_g"], json.dumps([{"label": f["serving"][0], "grams": f["serving"][1]}]),
             json.dumps({"fiber": f["fibre_g"]}))).lastrowid]
    name, servings, items = RECIPE
    recipe = db.execute("SELECT food_id FROM recipes WHERE club_id = ? AND owner_id IS NULL AND name = ?",
                        (club_id, name)).fetchone()
    total = sum(grams for _, grams in items)
    if recipe is None:
        foods = [(db.execute("SELECT * FROM foods WHERE name_bg = ? AND owner_id IS NULL AND club_id IS NULL",
                             (food,)).fetchone(), grams) for food, grams in items]
        per100 = {k: sum(f[k] * g for f, g in foods) / total for k in ("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g")}
        micro = [json.loads(f["nutrients"] or "{}") for f, _ in foods]
        nutrients = {k: sum(m[k] * g for m, (_, g) in zip(micro, foods)) / total
                     for k in sorted(set.intersection(*(set(m) for m in micro)))}
        food_id = db.execute(
            "INSERT INTO foods(club_id, name_bg, kcal, protein_g, carbs_g, fat_g, fibre_g, servings, nutrients, source) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'recipe')",
            (club_id, name, *per100.values(), json.dumps(recipe_servings(total, servings)), json.dumps(nutrients))).lastrowid
        recipe_id = db.execute("INSERT INTO recipes(club_id, name, servings_count, food_id) VALUES (?, ?, ?, ?)",
                               (club_id, name, servings, food_id)).lastrowid
        db.executemany("INSERT INTO recipe_items(recipe_id, position, food_id, grams) VALUES (?, ?, ?, ?)",
                       [(recipe_id, n, f["id"], g) for n, (f, g) in enumerate(foods)])
        recipe = [food_id]
    return {"scanned": (bar[0], SCANNED_FOOD["serving"][0], SCANNED_FOOD["serving"][1]),
            "recipe": (recipe[0], "serving", round(total / servings, 1))}


def seed_training_nutrition(db, user_ids, athlete_ids, groups, club_ids, today, new_athletes):
    """Seed synthetic exercises, plans, sets and nutrition in the demo clubs."""
    exercise_ids = {}
    for club_id in club_ids:
        for name, kind, lower in EXERCISES:
            db.execute("INSERT OR IGNORE INTO exercises(club_id, name, kind, lower_body) VALUES (?, ?, ?, ?)",
                       (club_id, name, kind, lower))
    demo_club_id = club_ids[0]
    for row in db.execute("SELECT id, name FROM exercises WHERE club_id = ?", (demo_club_id,)):
        exercise_ids[row["name"]] = row["id"]
    starts_on = (today - timedelta(days=today.weekday() + 14)).isoformat()
    plans = {}
    for group_name, title in (("Юноши", "Сила и скокове: юноши"), ("Състезатели", "Сила и скокове: състезатели")):
        existing = db.execute("SELECT id FROM plans WHERE club_id = ? AND group_id = ? AND notes = ? AND title = ?",
                              (demo_club_id, groups[group_name], PLAN_NOTE, title)).fetchone()
        if existing:
            plans[group_name] = existing[0]
            continue
        cursor = db.execute(
            "INSERT INTO plans(club_id, title, group_id, athlete_id, starts_on, weeks, notes, created_by) "
            "VALUES (?, ?, ?, NULL, ?, 6, ?, ?)",
            (demo_club_id, title, groups[group_name], starts_on, PLAN_NOTE, user_ids["coach"]))
        for position, (weekday, name, sets, reps, reps_max, load, rpe) in enumerate(PLAN_ITEMS):
            db.execute(
                "INSERT INTO plan_items(plan_id, weekday, exercise_id, sets, reps, reps_max, load_kg, rpe_target, "
                "position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (cursor.lastrowid, weekday, exercise_ids[name], sets, reps, reps_max, load, rpe, position))
        plans[group_name] = cursor.lastrowid
    weekdays = sorted({item[0] for item in PLAN_ITEMS})
    food_ids = {name: db.execute("SELECT id FROM foods WHERE name_bg = ? AND owner_id IS NULL",
                                 (name,)).fetchone()[0] for _, name, _ in MEAL_PLAN}
    swaps = seed_food_v2(db, demo_club_id)
    for group_name, accounts in DEMO_GROUPS.items():
        for account in accounts:
            if account not in new_athletes:
                continue
            athlete_id, variant = athlete_ids[account], int(account[-1])
            rng = random.Random(1000 + variant)
            for session, days_ago in enumerate((12, 10, 8, 5, 3, 1)):
                day = (today - timedelta(days=days_ago)).isoformat()
                for weekday, name, sets, reps, reps_max, load, rpe in PLAN_ITEMS:
                    if weekday != weekdays[session % len(weekdays)]:
                        continue
                    for set_no in range(1, sets + 1):
                        if variant == 1:  # reaches the top of every range: a progression suggestion
                            done, effort = reps_max, (rpe - 1 if rpe else None)
                        elif variant == 2:  # stays below the bottom of the range: a lighter suggestion
                            done, effort = max(1, reps - 1), (rpe + 1 if rpe else None)
                        else:
                            done, effort = rng.randint(reps, reps_max), (rpe + rng.choice((-1, -.5, 0, .5)) if rpe else None)
                        db.execute(
                            "INSERT INTO set_logs(athlete_id, day, exercise_id, set_no, reps, load_kg, rpe, plan_id, "
                            "client_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            (athlete_id, day, exercise_ids[name], set_no, done, load, effort, plans[group_name],
                             f"seed-set-{athlete_id}-{day}-{exercise_ids[name]}-{set_no}", now_isos(today - timedelta(days=days_ago))))
            base = 60 + variant * 3 + rng.random()
            for days_ago in range(14):
                day = today - timedelta(days=days_ago)
                if days_ago != 4 + variant % 3:
                    for meal, food, grams in MEAL_PLAN:
                        food_id, serving, grams = food_ids[food], None, round(grams * rng.uniform(.85, 1.15))
                        if days_ago < 7 and food in WEEK_SWAPS:
                            food_id, serving, grams = swaps[WEEK_SWAPS[food]]
                        db.execute(
                            "INSERT INTO food_logs(athlete_id, day, meal, food_id, grams, client_id, created_at, "
                            "serving_label, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            (athlete_id, day.isoformat(), meal, food_id, grams,
                             f"seed-food-{athlete_id}-{day.isoformat()}-{food}", now_isos(day), serving,
                             None if serving is None else 1))
                # Two days without a weigh-in; the coaching athlete gets one consistent series in seed_coaching.
                if days_ago not in (4, 9) and account != "athlete6":
                    db.execute("INSERT INTO weights(athlete_id, day, kg) VALUES (?, ?, ?)",
                               (athlete_id, day.isoformat(), round(base + days_ago * .04 + rng.gauss(0, .3), 1)))
    template_id = seed_workout_templates(db, user_ids, exercise_ids, demo_club_id)
    if "athlete1" in new_athletes:
        seed_workout(db, athlete_ids["athlete1"], template_id, today)
    for account, kcal in (("athlete1", 2200), ("athlete7", 2600)):
        if account not in new_athletes:
            continue
        db.execute(
            "INSERT INTO nutrition_targets(athlete_id, kcal, protein_g, carbs_g, fat_g, set_by, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (athlete_ids[account], kcal, 130, round(kcal * .5 / 4), round(kcal * .3 / 9), user_ids.get("coach"),
             datetime.now(timezone.utc).isoformat()))
    if "athlete6" in new_athletes:
        seed_coaching(db, athlete_ids["athlete6"], user_ids["coach"], food_ids, today)


# owner, shared with the demo club, title, items: exercise, sets, reps, load_kg, rest_s, superset
TEMPLATES = (
    ("coach", True, "Долна част: сила", (("Клякане със щанга", 3, 6, 60, 150, None), ("Румънска тяга", 3, 8, 50, 120, None),
                                         ("Напади с дъмбели", 3, 10, 20, 60, 1), ("Планк", 3, 1, None, 60, 1))),
    ("athlete1", False, "Моята бърза тренировка", (("Лежанка", 3, 8, 40, 120, None), ("Набирания", 3, 6, None, 120, None))),
)


def seed_workout_templates(db, user_ids, exercise_ids, club_id):
    """Two templates (one shared by the coach, one private to athlete1), idempotent; returns the shared one's id."""
    ids = []
    for owner, shared, title, items in TEMPLATES:
        row = db.execute("SELECT id FROM workout_templates WHERE owner_id = ? AND title = ?",
                         (user_ids[owner], title)).fetchone()
        if row is None:
            row = db.execute("INSERT INTO workout_templates(owner_id, club_id, title, items) VALUES (?, ?, ?, ?) RETURNING id", (
                user_ids[owner], club_id if shared else None, title, json.dumps([
                    {"exercise_id": exercise_ids[name], "sets": sets, "reps": reps, "load_kg": load, "rest_s": rest,
                     "superset": superset} for name, sets, reps, load, rest, superset in items]))).fetchone()
        ids.append(row[0])
    return ids[0]


def seed_workout(db, athlete_id, template_id, today):
    """One finished demo workout holding the athlete's most recent seeded sets."""
    day = db.execute("SELECT MAX(day) FROM set_logs WHERE athlete_id = ? AND load_kg IS NOT NULL", (athlete_id,)).fetchone()[0]
    start = datetime.fromisoformat(day + "T16:30:00+00:00")
    workout_id = db.execute(
        "INSERT INTO workouts(athlete_id, started_at, ended_at, title, template_id, notes, client_id) "
        "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
        (athlete_id, start.isoformat(), (start + timedelta(minutes=64)).isoformat(), "Демо тренировка",
         template_id, "Синтетични данни", f"seed-workout-{athlete_id}")).fetchone()[0]
    db.execute("UPDATE set_logs SET workout_id = ? WHERE athlete_id = ? AND day = ?", (workout_id, athlete_id, day))


def seed_coaching(db, athlete_id, coach_id, food_ids, today):
    """One demo athlete with a long, complete diary and weigh-in history so an adaptive program is shown."""
    rng = random.Random(7777)
    base = 72.0
    for days_ago in range(48):
        d = today - timedelta(days=days_ago)
        iso = d.isoformat()
        if not db.execute("SELECT 1 FROM food_logs WHERE athlete_id = ? AND day = ?", (athlete_id, iso)).fetchone():
            for meal, food, grams in MEAL_PLAN:
                db.execute(
                    "INSERT INTO food_logs(athlete_id, day, meal, food_id, grams, client_id, created_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (athlete_id, iso, meal, food_ids[food], round(grams * rng.uniform(.9, 1.1)),
                     f"seed-coach-food-{athlete_id}-{iso}-{food}", now_isos(d)))
        if not db.execute("SELECT 1 FROM weights WHERE athlete_id = ? AND day = ?", (athlete_id, iso)).fetchone():
            db.execute("INSERT INTO weights(athlete_id, day, kg) VALUES (?, ?, ?)",
                       (athlete_id, iso, round(base + days_ago * .03 + rng.gauss(0, .2), 1)))
    for days_ago in range(42):  # mark the recent, continuous span of diary days explicitly complete
        iso = (today - timedelta(days=days_ago)).isoformat()
        rows = [[r["id"], r["meal"], r["food_id"], r["grams"]] for r in db.execute(
            "SELECT id, meal, food_id, grams FROM food_logs WHERE athlete_id = ? AND day = ? ORDER BY id",
            (athlete_id, iso))]
        fingerprint = hashlib.blake2b(json.dumps(rows, separators=(",", ":")).encode("utf-8")).hexdigest()
        db.execute("INSERT OR IGNORE INTO nutrition_day_complete(athlete_id, day, diary_fingerprint, confirmed_at) "
                   "VALUES (?, ?, ?, ?)", (athlete_id, iso, fingerprint, now_isos(today)))
    latest = db.execute("SELECT kg FROM weights WHERE athlete_id = ? ORDER BY day DESC LIMIT 1", (athlete_id,)).fetchone()[0]
    db.execute(
        "INSERT INTO coaching_programs(athlete_id, effective_date, seed_kcal, mode, goal, rate_kg_week, "
        "protein_g, fat_g, applied, set_by, updated_at, target_weight_kg, end_date) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (athlete_id, (today - timedelta(days=45)).isoformat(), 2600.0, "updating", "lose", 0.3, 150.0, 70.0,
         0, coach_id, datetime.now(timezone.utc).isoformat(), round(latest - 2, 1), (today + timedelta(days=49)).isoformat()))


def now_isos(day):
    return min(datetime(day.year, day.month, day.day, 20, tzinfo=timezone.utc), datetime.now(timezone.utc)).isoformat()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reset-demo", action="store_true", help="Replace records for the fixed demo accounts.")
    seed(reset=parser.parse_args().reset_demo)
    print("Synthetic demo data is ready. Demo password: demo1234")

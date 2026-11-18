"""Create synthetic demo accounts, jumps and club data, once per database."""

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
import random
import secrets

from .main import Checkin, Jump, database, database_path, init_db, password_hash

SOFIA = ZoneInfo("Europe/Sofia")


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
            # Local start times in Bulgarian club time, on round quarter hours, stored as UTC.
            sessions = (
                # title, days offset from today, local hour:minute, group, test, trials, place, cancelled
                ("Тренировка: скокове от платформа", -20, 17, 30, "Юноши", "drop_jump", 3, "Зала 1", False),
                ("Контролен тест — скок с приклякане", -10, 18, 15, None, "cmj", 3, "Зала 2", False),
                ("Седмичен тест на отбора", -3, 16, 45, "Състезатели", "drop_jump", 3, "Зала 1", False),
                ("Контролно тестване на формата", 2, 19, 0, None, "cmj", 3, "Зала 2", False),
                ("Подготовка за състезание", 7, 17, 30, "Състезатели", "drop_jump", 3, "Зала 1", True),
            )
            session_ids = []
            for title, offset, hour, minute, group_name, test, trials, place, cancelled in sessions:
                local = datetime(
                    *(datetime.now(SOFIA).date() + timedelta(days=offset)).timetuple()[:3],
                    hour, minute, tzinfo=SOFIA)
                starts_at = local.astimezone(timezone.utc).isoformat()
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
        # Each athlete has their own stable wellbeing level and a personal weekly training rhythm
        # (a sport-day skipping pattern plus item-level noise), so days vary naturally without
        # looking like a test fixture. athlete2 drops clearly below their own usual level today.
        rng = random.Random(20261118)
        if not db.execute("SELECT COUNT(*) FROM checkins").fetchone()[0]:
            today = datetime.now(timezone.utc).date()
            for account in athlete_ids:
                athlete_id = athlete_ids[account]
                variant = int(account[-1])
                level = 4 if variant % 2 == 0 else 3
                # Personal training days (offset within a two-week beat), per athlete.
                train_days = {0, 1, 3, 6, 8, 10, 12} if variant % 2 == 0 else {0, 2, 3, 5, 7, 10, 12}
                for days_ago in range(14):
                    day = today - timedelta(days=days_ago)
                    if account == "athlete2" and days_ago == 0:
                        # Below his own usual, but still a real day — not five 1s.
                        mood, sleep, fatigue, soreness, stress = 2, 3, 2, 3, 2
                    elif days_ago == 0:
                        # Everyone else reports their typical day, so exactly one athlete stands out.
                        mood, sleep, fatigue, soreness, stress = [level] * 5
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

        seed_training_nutrition(db, user_ids, athlete_ids, groups)
        db.commit()


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


def seed_training_nutrition(db, user_ids, athlete_ids, groups):
    """Exercises, group plans, two weeks of sets, meals and weights; synthetic, only into an empty set of tables."""
    exercise_ids = {}
    for club in db.execute("SELECT id FROM clubs").fetchall():
        for name, kind, lower in EXERCISES:
            db.execute("INSERT OR IGNORE INTO exercises(club_id, name, kind, lower_body) VALUES (?, ?, ?, ?)",
                       (club["id"], name, kind, lower))
    demo_club_id = db.execute("SELECT club_id FROM users WHERE email = 'coach@demo.test'").fetchone()[0]
    for row in db.execute("SELECT id, name FROM exercises WHERE club_id = ?", (demo_club_id,)):
        exercise_ids[row["name"]] = row["id"]
    today = datetime.now(timezone.utc).date()
    if db.execute("SELECT COUNT(*) FROM plans").fetchone()[0]:
        return
    starts_on = (today - timedelta(days=today.weekday() + 14)).isoformat()
    plans = {}
    for group_name, title in (("Юноши", "Сила и скокове: юноши"), ("Състезатели", "Сила и скокове: състезатели")):
        cursor = db.execute(
            "INSERT INTO plans(club_id, title, group_id, athlete_id, starts_on, weeks, notes, created_by) "
            "VALUES (?, ?, ?, NULL, ?, 6, ?, ?)",
            (demo_club_id, title, groups[group_name], starts_on, "Демо план: синтетични данни",
             user_ids.get("coach")))
        for position, (weekday, name, sets, reps, reps_max, load, rpe) in enumerate(PLAN_ITEMS):
            db.execute(
                "INSERT INTO plan_items(plan_id, weekday, exercise_id, sets, reps, reps_max, load_kg, rpe_target, "
                "position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (cursor.lastrowid, weekday, exercise_ids[name], sets, reps, reps_max, load, rpe, position))
        plans[group_name] = cursor.lastrowid
    members = {"Юноши": ("athlete1", "athlete2", "athlete4"), "Състезатели": ("athlete5", "athlete6", "athlete7")}
    weekdays = sorted({item[0] for item in PLAN_ITEMS})
    food_ids = {name: db.execute("SELECT id FROM foods WHERE name_bg = ? AND owner_id IS NULL",
                                 (name,)).fetchone()[0] for _, name, _ in MEAL_PLAN}
    for group_name, accounts in members.items():
        for account in accounts:
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
                if days_ago != 5:  # one day without a meal log
                    for meal, food, grams in MEAL_PLAN:
                        db.execute(
                            "INSERT INTO food_logs(athlete_id, day, meal, food_id, grams, client_id, created_at) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                            (athlete_id, day.isoformat(), meal, food_ids[food], round(grams * rng.uniform(.85, 1.15)),
                             f"seed-food-{athlete_id}-{day.isoformat()}-{food}", now_isos(day)))
                if days_ago not in (4, 9):  # two days without a weigh-in
                    db.execute("INSERT INTO weights(athlete_id, day, kg) VALUES (?, ?, ?)",
                               (athlete_id, day.isoformat(), round(base + days_ago * .04 + rng.gauss(0, .3), 1)))
    db.execute("UPDATE users SET under_18 = 1 WHERE email = 'athlete7@demo.test'")
    for account, kcal in (("athlete1", 2200), ("athlete7", 2600)):
        db.execute(
            "INSERT INTO nutrition_targets(athlete_id, kcal, protein_g, carbs_g, fat_g, set_by, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (athlete_ids[account], kcal, 130, round(kcal * .5 / 4), round(kcal * .3 / 9), user_ids.get("coach"),
             datetime.now(timezone.utc).isoformat()))


def now_isos(day):
    return datetime(day.year, day.month, day.day, 20, 0, 0, tzinfo=timezone.utc).isoformat()


if __name__ == "__main__":
    seed()
    print("Synthetic demo data is ready. Demo password: demo1234")
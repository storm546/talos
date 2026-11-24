"""Built-in exercise library and ready-made training programs (SPEC 10.8).

Every club gets the library once (a coach may delete entries afterwards; they do not come back). A program is a
few workout days; adopting one creates ordinary workout templates, private for an athlete and shared with the club
for staff, so the workout screen, the offline queue and progression work unchanged.

`init_schema(db)` is called from `main.init_db`; `install(app, h)` from `main.create_app`.
"""

import json
from typing import Literal

from fastapi import Depends, HTTPException, Request

# name, kind, lower body. Common gym and home exercises; Bulgarian names like the rest of the club catalogue.
LIBRARY = (
    ("Клякане със щанга", "strength", 1), ("Предно клякане", "strength", 1), ("Гоблет клякане", "strength", 1),
    ("Клякане без тежест", "strength", 1), ("Мъртва тяга", "strength", 1), ("Румънска тяга", "strength", 1),
    ("Лег преса", "strength", 1), ("Напади с дъмбели", "strength", 1), ("Български сплит клек", "strength", 1),
    ("Хип тръст", "strength", 1), ("Глутеус мост", "strength", 1), ("Сгъване на бедрата на машина", "strength", 1),
    ("Разгъване на бедрата на машина", "strength", 1), ("Повдигане на пръсти", "strength", 1),
    ("Лежанка", "strength", 0), ("Лежанка с дъмбели на наклон", "strength", 0), ("Лицеви опори", "strength", 0),
    ("Кофички", "strength", 0), ("Раменна преса със щанга", "strength", 0), ("Раменна преса с дъмбели", "strength", 0),
    ("Странично повдигане с дъмбели", "strength", 0), ("Разгъване за трицепс на скрипец", "strength", 0),
    ("Набирания", "strength", 0), ("Обърнато гребане", "strength", 0), ("Гребане със щанга", "strength", 0),
    ("Гребане с дъмбел", "strength", 0), ("Вертикален скрипец", "strength", 0), ("Хоризонтален скрипец", "strength", 0),
    ("Фейс пул", "strength", 0), ("Сгъване за бицепс с дъмбели", "strength", 0),
    ("Планк", "mobility", 0), ("Повдигане на крака от вис", "strength", 0), ("Мобилност за глезени", "mobility", 0),
    ("Леко бягане", "conditioning", 0), ("Скачане на въже", "conditioning", 0), ("Гребен ергометър", "conditioning", 0),
)

# English names for the program preview in the English app; adopted templates use the club's (Bulgarian) names.
NAMES_EN = {
    "Клякане със щанга": "Back squat", "Предно клякане": "Front squat", "Гоблет клякане": "Goblet squat",
    "Клякане без тежест": "Bodyweight squat", "Мъртва тяга": "Deadlift", "Румънска тяга": "Romanian deadlift",
    "Лег преса": "Leg press", "Напади с дъмбели": "Dumbbell lunge", "Български сплит клек": "Bulgarian split squat",
    "Хип тръст": "Hip thrust", "Глутеус мост": "Glute bridge", "Сгъване на бедрата на машина": "Leg curl",
    "Разгъване на бедрата на машина": "Leg extension", "Повдигане на пръсти": "Calf raise",
    "Лежанка": "Bench press", "Лежанка с дъмбели на наклон": "Incline dumbbell press", "Лицеви опори": "Push-up",
    "Кофички": "Dip", "Раменна преса със щанга": "Overhead press", "Раменна преса с дъмбели": "Dumbbell shoulder press",
    "Странично повдигане с дъмбели": "Lateral raise", "Разгъване за трицепс на скрипец": "Triceps pushdown",
    "Набирания": "Pull-up", "Обърнато гребане": "Inverted row", "Гребане със щанга": "Barbell row",
    "Гребане с дъмбел": "Dumbbell row", "Вертикален скрипец": "Lat pulldown", "Хоризонтален скрипец": "Seated cable row",
    "Фейс пул": "Face pull", "Сгъване за бицепс с дъмбели": "Dumbbell curl",
    "Планк": "Plank", "Повдигане на крака от вис": "Hanging leg raise", "Мобилност за глезени": "Ankle mobility",
    "Леко бягане": "Easy run", "Скачане на въже": "Jump rope", "Гребен ергометър": "Rowing machine",
}
DAYS_EN = {"Горна A": "Upper A", "Долна A": "Lower A", "Горна B": "Upper B", "Долна B": "Lower B",
           "Бутане": "Push", "Дърпане": "Pull", "Крака": "Legs", "У дома": "Home"}
HOLDS = {"Планк"}  # reps are holds of 30-45 s

# Program key -> titles, days per week, and days of (exercise, sets, reps, rest seconds). Plank reps are sets of
# about 30-45 s (HOLDS), as in the club plans. Loads are left to the workout screen, which pre-fills from the last session.
PROGRAMS = {
    "full_body": {
        "title_bg": "Цяло тяло (начинаещи)", "title_en": "Full body (beginner)", "per_week": 3,
        "note_bg": "Редувайте A и B през ден, 3 пъти седмично. Добавяйте 2,5 kg, когато изпълните всички повторения.",
        "note_en": "Alternate A and B every other day, 3 times a week. Add 2.5 kg once every rep is done.",
        "days": {"A": [("Клякане със щанга", 3, 5, 180), ("Лежанка", 3, 5, 180), ("Гребане със щанга", 3, 8, 120),
                       ("Планк", 3, 1, 60)],
                 "B": [("Мъртва тяга", 3, 5, 180), ("Раменна преса със щанга", 3, 5, 150),
                       ("Вертикален скрипец", 3, 10, 90), ("Повдигане на крака от вис", 3, 10, 60)]}},
    "upper_lower": {
        "title_bg": "Горна/долна част", "title_en": "Upper/lower", "per_week": 4,
        "note_bg": "Четири дни: горна A, долна A, почивка, горна B, долна B.",
        "note_en": "Four days: upper A, lower A, rest, upper B, lower B.",
        "days": {"Горна A": [("Лежанка", 4, 6, 150), ("Гребане със щанга", 4, 8, 120),
                             ("Раменна преса с дъмбели", 3, 10, 90), ("Вертикален скрипец", 3, 10, 90),
                             ("Сгъване за бицепс с дъмбели", 2, 12, 60)],
                 "Долна A": [("Клякане със щанга", 4, 6, 180), ("Румънска тяга", 3, 8, 120),
                             ("Напади с дъмбели", 3, 10, 90), ("Повдигане на пръсти", 3, 12, 60), ("Планк", 3, 1, 60)],
                 "Горна B": [("Раменна преса със щанга", 4, 6, 150), ("Набирания", 4, 6, 120),
                             ("Лежанка с дъмбели на наклон", 3, 10, 90), ("Хоризонтален скрипец", 3, 10, 90),
                             ("Разгъване за трицепс на скрипец", 2, 12, 60)],
                 "Долна B": [("Мъртва тяга", 3, 5, 180), ("Предно клякане", 3, 8, 150), ("Хип тръст", 3, 10, 90),
                             ("Сгъване на бедрата на машина", 3, 12, 60), ("Повдигане на крака от вис", 3, 10, 60)]}},
    "push_pull_legs": {
        "title_bg": "Бутане/дърпане/крака", "title_en": "Push/pull/legs", "per_week": 3,
        "note_bg": "Три дни в седмицата, или два пъти подред (6 дни) за напреднали.",
        "note_en": "Three days a week, or twice through (6 days) when experienced.",
        "days": {"Бутане": [("Лежанка", 4, 6, 150), ("Раменна преса с дъмбели", 3, 8, 120), ("Кофички", 3, 10, 90),
                            ("Странично повдигане с дъмбели", 3, 15, 60), ("Разгъване за трицепс на скрипец", 3, 12, 60)],
                 "Дърпане": [("Мъртва тяга", 3, 5, 180), ("Набирания", 4, 6, 120), ("Гребане с дъмбел", 3, 10, 90),
                             ("Фейс пул", 3, 15, 60), ("Сгъване за бицепс с дъмбели", 3, 12, 60)],
                 "Крака": [("Клякане със щанга", 4, 6, 180), ("Румънска тяга", 3, 8, 120), ("Лег преса", 3, 10, 120),
                           ("Разгъване на бедрата на машина", 3, 12, 60), ("Повдигане на пръсти", 4, 12, 60)]}},
    "home": {
        "title_bg": "У дома без уреди", "title_en": "Home, no equipment", "per_week": 3,
        "note_bg": "3 пъти седмично. Когато всички серии станат лесни, добавете повторения или забавете спускането.",
        "note_en": "3 times a week. When every set feels easy, add reps or slow the lowering.",
        "days": {"У дома": [("Клякане без тежест", 3, 15, 60), ("Лицеви опори", 3, 10, 60),
                            ("Обърнато гребане", 3, 10, 60), ("Български сплит клек", 3, 10, 60),
                            ("Глутеус мост", 3, 15, 45), ("Планк", 3, 1, 45)]}},
}


def init_schema(db):
    db.execute("CREATE TABLE IF NOT EXISTS club_library (club_id INTEGER PRIMARY KEY REFERENCES clubs(id) ON DELETE CASCADE)")
    for (club_id,) in db.execute("SELECT id FROM clubs WHERE id NOT IN (SELECT club_id FROM club_library)").fetchall():
        add_library(db, club_id)


def add_library(db, club_id):
    """Once per club: names the club already has are kept as they are."""
    if db.execute("INSERT OR IGNORE INTO club_library(club_id) VALUES (?)", (club_id,)).rowcount:
        db.executemany("INSERT OR IGNORE INTO exercises(club_id, name, kind, lower_body) VALUES (?, ?, ?, ?)",
                       [(club_id, *row) for row in LIBRARY])


def public(key, program):
    return {"key": key, **{k: v for k, v in program.items() if k != "days"},
            "days": [{"name": name, "name_en": DAYS_EN.get(name, name),
                      "items": [{"exercise": e, "exercise_en": NAMES_EN[e], "sets": s, "reps": r, "rest_s": rest,
                                 "hold": e in HOLDS} for e, s, r, rest in items]}
                     for name, items in program["days"].items()]}


def install(app, h):
    current_user, get_db = h.current_user, h.get_db

    @app.get("/api/programs")
    def list_programs(user=Depends(current_user), db=Depends(get_db)):
        return [public(key, program) for key, program in PROGRAMS.items()]

    @app.post("/api/programs/{key}/adopt", status_code=201, dependencies=[Depends(current_user)])
    def adopt_program(key: Literal[tuple(PROGRAMS)], request: Request, db=Depends(get_db)):
        """Creates one workout template per day; a day already adopted (same title, same owner) is not repeated."""
        with h.authorized_write(db, request) as (user, emit):
            if user["club_id"] is None:
                raise HTTPException(422, "Programs need a club exercise catalogue")
            if user["role"] == "athlete" and not user["consent_recorded"]:
                raise HTTPException(403, "Consent must be recorded by a coach or admin before recording workout templates")
            shared = None if user["role"] == "athlete" else user["club_id"]
            add_library(db, user["club_id"])
            ids = {row["name"]: row["id"] for row in db.execute("SELECT id, name FROM exercises WHERE club_id = ?",
                                                               (user["club_id"],))}
            program, created, skipped = PROGRAMS[key], [], set()
            for name, items in program["days"].items():
                title = f"{program['title_bg']} · {name}" if len(program["days"]) > 1 else program["title_bg"]
                rows = [{"exercise_id": ids[e], "sets": s, "reps": r, "load_kg": None, "rest_s": rest, "superset": None}
                        for e, s, r, rest in items if e in ids]
                skipped.update(e for e, *_ in items if e not in ids)  # a coach deleted it from the club
                if not rows or db.execute("SELECT 1 FROM workout_templates WHERE owner_id = ? AND title = ? "
                                          "AND club_id IS ?", (user["id"], title, shared)).fetchone():
                    continue
                template_id = db.execute("INSERT INTO workout_templates(owner_id, club_id, title, items) VALUES (?, ?, ?, ?)",
                                         (user["id"], shared, title, json.dumps(rows))).lastrowid
                h.audit(db, user["id"], "workout_template_created", template_id, {"program": key})
                created.append({"id": template_id, "title": title})
        return {"program": key, "created": created, "missing_exercises": sorted(skipped)}

    return {"list_programs": list_programs}

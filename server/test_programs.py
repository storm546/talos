"""Exercise library and ready-made programs (SPEC 10.8)."""

from server import main, programs_api
from server.test_server import api, auth, template, temporary_directory, user_id  # noqa: F401 (fixtures)
from server.test_training_nutrition_api import make_athlete


def test_every_program_exercise_is_in_the_library():
    names = {name for name, *_ in programs_api.LIBRARY}
    assert len(names) == len(programs_api.LIBRARY) and names == set(programs_api.NAMES_EN)
    for program in programs_api.PROGRAMS.values():
        for items in program["days"].values():
            assert {e for e, *_ in items} <= names
        assert set(program["days"]) <= set(programs_api.DAYS_EN) | {"A", "B"}


def test_adopt_creates_private_templates_once(api, auth):
    client, _ = api
    _, athlete = make_athlete(client, auth)
    programs = client.get("/api/programs", headers=athlete).json()
    assert [p["key"] for p in programs] == list(programs_api.PROGRAMS)
    response = client.post("/api/programs/upper_lower/adopt", headers=athlete)
    assert response.status_code == 201 and len(response.json()["created"]) == 4
    templates = client.get("/api/workout-templates", headers=athlete).json()
    mine = [t for t in templates if t["title"].startswith("Горна/долна част · ")]
    assert len(mine) == 4 and all(t["club_id"] is None for t in mine)
    lower_a = next(t for t in mine if t["title"].endswith("Долна A"))
    assert lower_a["items"][0]["exercise"] == "Клякане със щанга" and lower_a["items"][0]["sets"] == 4
    assert client.post("/api/programs/upper_lower/adopt", headers=athlete).json()["created"] == []  # not repeated
    assert client.post("/api/programs/nope/adopt", headers=athlete).status_code == 422
    # Other athletes do not see a private program.
    assert not [t for t in client.get("/api/workout-templates", headers=auth("athlete2")).json()
                if t["title"].startswith("Горна/долна част")]


def test_coach_shares_with_club_and_deleted_exercises_are_reported(api, auth):
    client, path = api
    coach = auth("coach")
    with main.database(path) as db:
        club = db.execute("SELECT club_id FROM users WHERE email = 'coach@demo.test'").fetchone()[0]
        db.execute("DELETE FROM exercises WHERE club_id = ? AND name = 'Фейс пул'", (club,))
    result = client.post("/api/programs/push_pull_legs/adopt", headers=coach).json()
    assert len(result["created"]) == 3 and result["missing_exercises"] == ["Фейс пул"]
    shared = [t for t in client.get("/api/workout-templates", headers=auth("athlete1")).json() if t["title"].startswith("Бутане")]
    assert shared and shared[0]["club_id"] == club
    with main.database(path) as db:  # the library is not added back on restart
        programs_api.init_schema(db)
        assert db.execute("SELECT 1 FROM exercises WHERE club_id = ? AND name = 'Фейс пул'", (club,)).fetchone() is None


def test_new_club_gets_the_library(api, auth):
    client, path = api
    club = client.post("/api/clubs", json={"name": "Нов клуб"}, headers=auth("admin")).json()["id"]
    exercises = client.get("/api/exercises", params={"club_id": club}, headers=auth("admin")).json()
    assert len(exercises) == len(programs_api.LIBRARY)

"""Measure representative API calls against a deterministic, disposable club database."""

from datetime import date, timedelta
import hashlib
import json
from math import ceil
from pathlib import Path
from resource import RUSAGE_SELF, getrusage
from statistics import median
import sys
from tempfile import TemporaryDirectory
from time import perf_counter
from unittest.mock import patch

from fastapi.testclient import TestClient

from . import assistant_api, main


NOW = "2026-11-19T10:00:00+00:00"
TODAY = date.fromisoformat(NOW[:10])


def populate(path):
    main.init_db(path)
    days = [(TODAY - timedelta(days=delta)).isoformat() for delta in range(365)]
    with main.database(path) as db:
        db.executemany("INSERT INTO clubs(id, name) VALUES (?, ?)", [(n, f"Club {n}") for n in range(1, 4)])
        users = [(n, (n - 1) // 100 + 1, "athlete") for n in range(1, 301)]
        users += [(301, 1, "coach"), (302, 1, "coach"), (303, 1, "coach"), (304, 1, "admin")]
        db.executemany("INSERT INTO users(id, club_id, email, name, role, password_salt, password_hash, "
                       "consent_recorded) VALUES (?, ?, ?, ?, ?, x'00', x'00', 1)",
                       [(n, club, f"bench-{n}@example.test", f"Person {n:03}", role) for n, club, role in users])
        db.executemany("INSERT INTO tokens VALUES (?, ?, 253402300799)",
                       [(main.token_hash(f"bench-{n}"), n) for n in (1, 301, 302, 303, 304)])
        fields = set(main.Jump.model_fields) - {"session_id", "client_id"}
        metrics = {**dict.fromkeys(fields, 1.25), "test": "cmj", "warnings": []}
        payloads = [json.dumps({**metrics, "knee_ankle_ratio": step / 100}) for step in range(10, 120)]
        db.executemany("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                       ((n % 300 + 1, days[n % 365] + NOW[10:], payloads[n % len(payloads)]) for n in range(50_000)))
        db.executemany("INSERT INTO checkins(athlete_id, day, sleep, fatigue, soreness, stress, mood, "
                       "training_minutes, rpe, created_at, updated_at) VALUES (?, ?, ?, 4, 4, 4, 4, 25, 5, ?, ?)",
                       ((n, day, 1 if step == 0 else 5, NOW, NOW) for n in range(1, 301) for step, day in enumerate(days)))
        foods = [row[0] for row in db.execute("SELECT id FROM foods WHERE club_id IS NULL AND owner_id IS NULL "
                                              "ORDER BY id LIMIT 3")]
        db.executemany("INSERT INTO food_logs(athlete_id, day, meal, food_id, grams, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                       ((n, day, meal, food, grams, NOW) for n in range(1, 301) for day in days
                        for meal, food, grams in zip(("breakfast", "lunch", "dinner"), foods, (95.1, 393.7, 134.2))))
        db.executemany("INSERT INTO weights(athlete_id, day, kg) VALUES (?, ?, ?)",
                       ((n, day, 70 + step / 365) for n in range(1, 301) for step, day in enumerate(days)))
        db.executemany("INSERT INTO nutrition_day_complete(athlete_id, day, diary_fingerprint, confirmed_at) "
                       "VALUES (1, ?, ?, ?)", ((day, hashlib.blake2b(json.dumps([list(row) for row in db.execute(
                           "SELECT id, meal, food_id, grams FROM food_logs WHERE athlete_id = 1 AND day = ? ORDER BY id", (day,))],
                           separators=(",", ":")).encode()).hexdigest(), NOW) for day in days))
        db.execute("INSERT INTO coaching_programs(athlete_id, goal, rate_kg_week, mode, effective_date, seed_kcal, "
                   "protein_g, fat_g, set_by, updated_at) VALUES (1, 'maintain', 0, 'updating', ?, 2500, 110, 64, 301, ?)",
                   (days[-1], NOW))


def benchmark(name, request):
    durations = []
    hashes = set()
    for iteration in range(28):
        start = perf_counter()
        response = request(iteration)
        elapsed = 1000 * (perf_counter() - start)
        if response.status_code != 200:
            raise RuntimeError((name, response.status_code, response.text[:500]))
        if iteration >= 3:
            durations.append(elapsed)
        if "json" in response.headers.get("content-type", ""):
            result = response.json()
            if isinstance(result, dict):
                for key in ("exported_at", "conversation_id"):
                    result.pop(key, None)
            content = json.dumps(result, sort_keys=True, ensure_ascii=False).encode()
        else:
            content = response.content
        hashes.add(hashlib.sha256(content).hexdigest()[:16])
    print(f"{name:24} {median(durations):8.2f} {sorted(durations)[ceil(len(durations) * .95) - 1]:8.2f} "
          f"{len(response.content):9} {','.join(sorted(hashes))}", flush=True)


def mock_model(client, url, model, headers, messages, tools, *, allow_tools):
    if messages[-1]["role"] == "tool":
        return {"content": hashlib.sha256(messages[-1]["content"].encode()).hexdigest()}
    return {"content": None, "tool_calls": [{"id": "bench", "type": "function", "function": {
        "name": "team_summaries", "arguments": '{"limit": 100}'}}]}


def run():
    filters = sys.argv[1:]

    def selected(name):
        return not filters or name in filters

    def headers(user):
        return {"Authorization": f"Bearer bench-{user}"}

    with TemporaryDirectory(dir=Path(__file__).parent, prefix="test-") as temporary:
        path = Path(temporary) / "benchmark.db"
        populate(path)
        with (TestClient(main.create_app(path), raise_server_exceptions=False) as client,
              patch("server.training_nutrition_api.utc_today", return_value=TODAY),
              patch.object(assistant_api, "model_client", return_value=("mock", "mock", {})),
              patch.object(assistant_api, "call_model", mock_model)):
            endpoints = {
                "coach athletes": (301, "/api/athletes?limit=100"),
                "admin athletes": (304, "/api/athletes?limit=300"),
                "coach team checkins": (301, f"/api/checkins/team?day={TODAY}"),
                "athlete history": (1, "/api/jumps?limit=500"),
                "athlete jump summary": (1, "/api/athletes/1/summary"),
                "athlete JSON export": (1, "/api/me/export"),
                "coach CSV export": (301, "/api/athletes/1/export.csv"),
                "nutrition summary": (301, f"/api/athletes/1/nutrition/summary?day={TODAY}"),
                "nutrition program": (301, "/api/athletes/1/nutrition/program"),
                "expenditure": (301, "/api/athletes/1/nutrition/expenditure"),
                "nutrition estimate": (301, "/api/athletes/1/nutrition/estimate"),
                "nutrition target": (301, "/api/athletes/1/nutrition/target"),
                "weight history": (301, "/api/athletes/1/weights"),
                "weight CSV": (301, "/api/athletes/1/weights.csv"),
            }
            print("Endpoint                 Median ms   P95 ms     Bytes Response hashes")
            for name, (user, path) in endpoints.items():
                if selected(name):
                    benchmark(name, lambda _: client.get(path, headers=headers(user)))
            if selected("assistant roster tool"):
                benchmark("assistant roster tool", lambda iteration: client.post(
                    "/api/assistant", headers=headers(301 + iteration % 3), json={"message": "List athletes"}))
        status = Path("/proc/self/status").read_text().splitlines()
        print("; ".join(line for line in status if line.startswith(("VmRSS:", "VmHWM:"))))
        scope = Path("/sys/fs/cgroup") / Path("/proc/self/cgroup").read_text().strip().split(":", 2)[-1].lstrip("/")
        for key in ("memory.peak", "memory.max", "memory.events"):
            if (scope / key).exists():
                print(f"{key}: ", (scope / key).read_text().strip().replace("\n", ", "))
        print(f"Resource peak RSS: {getrusage(RUSAGE_SELF).ru_maxrss / 1024:.1f} MiB")


if __name__ == "__main__":
    run()

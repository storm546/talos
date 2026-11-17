"""Create synthetic demo accounts and ten jumps per athlete, once per database."""

from datetime import datetime, timedelta, timezone
import random
import secrets

from .main import Jump, database, database_path, init_db, password_hash


def seed(path=None):
    path = database_path() if path is None else path
    init_db(path)
    with database(path) as db:
        db.execute("BEGIN IMMEDIATE")
        for club_name, accounts in (
            ("Демо клуб", (("admin", "Демо администратор", "admin"), ("coach", "Демо треньор", "coach"),
                           ("athlete1", "Александра Иванова", "athlete"), ("athlete2", "Борис Петров", "athlete"),
                           ("athlete4", "Виктория Георгиева", "athlete"), ("athlete5", "Даниел Стоянов", "athlete"),
                           ("athlete6", "Елена Димитрова", "athlete"), ("athlete7", "Мартин Колев", "athlete"))),
            ("Втори демо клуб", (("coach2", "Втори демо треньор", "coach"), ("athlete3", "Иван Николов", "athlete"))),
        ):
            db.execute("INSERT OR IGNORE INTO clubs(name) VALUES (?)", (club_name,))
            club_id = db.execute("SELECT id FROM clubs WHERE name = ?", (club_name,)).fetchone()[0]
            for account, name, role in accounts:
                email = account + "@demo.test"
                if db.execute("SELECT id FROM users WHERE email = ?", (email,)).fetchone():
                    continue
                salt = secrets.token_bytes(16)
                cursor = db.execute(
                    "INSERT INTO users(club_id, email, name, role, password_salt, password_hash, "
                    "consent_recorded, consent_by, consent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (club_id, email, name, role, salt, password_hash("demo1234", salt), role == "athlete",
                     "Synthetic demo consent" if role == "athlete" else None,
                     datetime.now(timezone.utc).isoformat() if role == "athlete" else None),
                )
                if role != "athlete":
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


if __name__ == "__main__":
    seed()
    print("Synthetic demo data is ready. Demo password: demo1234")

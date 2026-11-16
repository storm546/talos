"""Create synthetic demo accounts and ten jumps per athlete, once per database."""

from datetime import datetime, timedelta, timezone
import secrets

from .main import Jump, database, database_path, init_db, password_hash


def seed(path=None):
    path = database_path() if path is None else path
    init_db(path)
    with database(path) as db:
        db.execute("BEGIN IMMEDIATE")
        for club_name, accounts in (
            ("Demo Club", (("admin", "Demo Admin", "admin"), ("coach", "Demo Coach", "coach"),
                           ("athlete1", "Demo Athlete One", "athlete"), ("athlete2", "Demo Athlete Two", "athlete"))),
            ("Second Demo Club", (("coach2", "Second Demo Coach", "coach"), ("athlete3", "Second Demo Athlete", "athlete"))),
        ):
            db.execute("INSERT OR IGNORE INTO clubs(name) VALUES (?)", (club_name,))
            club_id = db.execute("SELECT id FROM clubs WHERE name = ?", (club_name,)).fetchone()[0]
            for account, name, role in accounts:
                email = account + "@demo.test"
                if db.execute("SELECT id FROM users WHERE email = ?", (email,)).fetchone():
                    continue
                salt = secrets.token_bytes(16)
                cursor = db.execute(
                    "INSERT INTO users(club_id, email, name, role, password_salt, password_hash) VALUES (?, ?, ?, ?, ?, ?)",
                    (club_id, email, name, role, salt, password_hash("demo1234", salt)),
                )
                if role != "athlete":
                    continue
                for index in range(10):
                    left, right = 65 + index, 67 + index
                    flight_ms = 360 + index * 5
                    metrics = Jump(
                        test="cmj" if index % 2 else "drop_jump", fps=60, flight_time_ms=flight_ms,
                        jump_height_cm=9.80665 * (flight_ms / 1000) ** 2 / 8 * 100,
                        knee_flexion_contact_l=20 + index / 2, knee_flexion_contact_r=22 + index / 2,
                        knee_flexion_peak_l=left, knee_flexion_peak_r=right, fppa_peak_l=5, fppa_peak_r=6,
                        knee_ankle_ratio=0.85 + index / 100, flexion_asymmetry_pct=abs(left - right) / max(left, right) * 100,
                        quality=0.95, warnings=[],
                    )
                    created_at = (datetime.now(timezone.utc) - timedelta(days=10 - index)).isoformat()
                    db.execute("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                               (cursor.lastrowid, created_at, metrics.model_dump_json()))


if __name__ == "__main__":
    seed()
    print("Synthetic demo data is ready. Demo password: demo1234")

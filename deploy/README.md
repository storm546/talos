# Deploying the JumpScan server (notes only)

Status: deployed at <https://jumpscan.stormlabs.cloud> with the "Without Docker (systemd)" path below, behind
Nginx Proxy Manager with a Let's Encrypt certificate, seeded with the synthetic demo accounts only.
Nothing in this repository publishes, pushes or restarts anything by itself.

The shape being deployed: one container running the API, a SQLite file in a named volume, and a reverse
proxy on the host that terminates TLS. The container port is published on `127.0.0.1:8171` only, so the
API is never reachable directly from the internet.

```
internet --443--> nginx / Nginx Proxy Manager --http--> 127.0.0.1:8171 -> container :8000
```

## 0. Prerequisites

- A home server with Docker and the Compose plugin (`docker compose version`).
- A DNS record (A or AAAA) pointing a name such as `jumpscan.example.com` at the server's public address;
  ports 80 and 443 forwarded to the server if it sits behind NAT.
- Certificates. The examples below use Let's Encrypt.

## 1. Get the code

```sh
git clone <repository-url> /srv/jumpscan
cd /srv/jumpscan
git checkout main          # deploy reviewed code, not the working branch
```

## 2. Build and start

Set `FORWARDED_ALLOW_IPS` in the shell or a Compose `.env` file to the trusted reverse proxy's
address as seen by uvicorn. Both Docker and systemd start uvicorn with `--proxy-headers` and
`--forwarded-allow-ips` using this value. Do not use `*`: only trusted proxies may supply client addresses.
For Docker bridge networking, a proxy on the same host normally appears as the Docker bridge gateway
(inspect the network's gateway); a proxy container on a shared network appears as that container's IP.
Use a stable proxy address and set that value, rather than assuming host loopback reaches the container.
For a direct Docker run, pass `-e FORWARDED_ALLOW_IPS=<proxy address>`; Compose requires the variable too.

```sh
export FORWARDED_ALLOW_IPS=<proxy-address-as-seen-by-the-container>
docker compose up -d --build
docker compose ps
curl -i http://127.0.0.1:8171/        # expect HTTP/1.1 200 and the dashboard HTML
docker inspect --format '{{.State.Health.Status}}' jumpscan   # expect healthy
```

The database file is `/data/jumpscan.db` inside the `jumpscan-data` volume. If you ever replace the
volume with a bind mount, make sure the mounted directory is writable by the unprivileged container user
(uid/gid of `jumpscan`, created in the image).

### Without Docker (systemd)

Use this where Docker is not available (for example inside an LXC container). Python 3.13 is assumed.

```sh
useradd --system --home-dir /srv/jumpscan --shell /usr/sbin/nologin jumpscan
git clone <repository-url> /srv/jumpscan && cd /srv/jumpscan && git checkout main
python3 -m venv .venv && .venv/bin/pip install -r server/requirements.txt
cp deploy/jumpscan.service /etc/systemd/system/
# If the reverse proxy runs on another machine, set HOST in the unit to this machine's LAN address.
# Also set FORWARDED_ALLOW_IPS in the unit to that proxy's address.
systemctl daemon-reload && systemctl enable --now jumpscan
curl -i http://127.0.0.1:8171/        # expect HTTP/1.1 200
```

The database is `/var/lib/jumpscan/jumpscan.db` (systemd creates the directory for the service user).
When the proxy is on the same host and connects over loopback, use `FORWARDED_ALLOW_IPS=127.0.0.1`
(the unit's default). This also applies to Docker with host networking when the proxy connects via
`127.0.0.1`; the supplied Compose deployment uses bridge networking instead.
For the commands below, replace `docker compose exec jumpscan python` with
`sudo -u jumpscan env JUMPSCAN_DB=/var/lib/jumpscan/jumpscan.db /srv/jumpscan/.venv/bin/python`, run from
`/srv/jumpscan`. Undo: `systemctl disable --now jumpscan`, remove the unit file, `/srv/jumpscan`,
`/var/lib/jumpscan` and the `jumpscan` user.

## 3. Create the first admin

Users are created through the API by an admin, so the first one has to be inserted once. Either use the
seed below (it creates `admin@demo.test`) or insert your own admin with this snippet, run on the host:

```sh
read -rsp "new admin password: " JSPW; echo
docker compose exec -T -e JSPW="$JSPW" jumpscan python - <<'PY'
import os, secrets
from server.main import database, database_path, password_hash

email = "admin@club.example"          # change this
password = os.environ["JSPW"]
salt = secrets.token_bytes(16)
with database(database_path()) as db:
    db.execute("INSERT OR IGNORE INTO clubs(name) VALUES ('Main Club')")
    club_id = db.execute("SELECT id FROM clubs WHERE name = 'Main Club'").fetchone()[0]
    db.execute(
        "INSERT INTO users(club_id, name, email, role, password_salt, password_hash)"
        " VALUES (?, ?, ?, 'admin', ?, ?)",
        (club_id, "Club Admin", email, salt, password_hash(password, salt)),
    )
    db.commit()
print("admin created:", email)
PY
unset JSPW
```

The password is passed as an environment variable, not as an argument, so it does not land in your shell
history. Then log in on the dashboard with that email and password.

Login is limited per email and client-address pair (5 failures per 15 minutes) and per client address
across all emails (30 failures per 15 minutes). Behind a reverse proxy the address is taken from the proxy's `X-Forwarded-For` header, so
configure `FORWARDED_ALLOW_IPS` as described above. Uvicorn accepts forwarded headers only from the
listed proxy addresses; untrusted clients cannot set their own rate-limit address through that header.

Role model for the rest: an admin creates coaches and athletes (`POST /api/users`), a coach creates
athletes in their own club. An admin, or a coach for athletes of their own club, sets a new password with
`POST /api/users/{id}/password` (also on the dashboard's Users page); this signs that user out everywhere.

## 4. Seed the demo accounts for the jury

Synthetic data only — no real athlete is involved:

```sh
docker compose exec -T jumpscan python -m server.seed --reset-demo
```

Creates club "Демо клуб" with `admin@demo.test`, `coach@demo.test` and six athletes
(`athlete1@demo.test`, `athlete2@demo.test`, `athlete4@demo.test` to `athlete7@demo.test`), and
"Втори демо клуб" with `coach2@demo.test`, `athlete3@demo.test`. Password for all of them: `demo1234`.
The reset replaces records for the exact demo accounts above, not all accounts in either club or at
`@demo.test`. It restores their passwords, consent and sample data, and signs out existing demo
sessions. It preserves non-demo accounts and their data, shared sessions or plans used by non-demo
accounts, and the audit log. Plain `python -m server.seed` is the non-destructive initial seed.

For a jury sandbox, schedule the reset nightly outside judging hours (for example at 03:00 in
`Europe/Sofia`). Run the Compose command from the checkout directory; for a systemd deployment,
run `python -m server.seed --reset-demo` from the checkout with the service's virtual environment,
user and `JUMPSCAN_DB` setting. Do not install it on a real club's database. The reset regenerates
recent jumps, technique sets and food logs, today's wellbeing check-ins, a test from one week ago
with a report, and an upcoming test. Warn testers that their demo changes are removed each night.

The rules ask for a test account for every role. Keep these, and give the credentials to the jury in the
documentation pack. Treat them as public credentials.

## 5. Reverse proxy with HTTPS

### Plain nginx

Install nginx and certbot, then:

```nginx
# /etc/nginx/sites-available/jumpscan
server {
    listen 80;
    server_name jumpscan.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    http2 on;
    server_name jumpscan.example.com;

    ssl_certificate     /etc/letsencrypt/live/jumpscan.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/jumpscan.example.com/privkey.pem;

    client_max_body_size 64k;          # the API caps requests at 16 KB
    add_header Strict-Transport-Security "max-age=31536000" always;   # browsers stay on HTTPS

    location = /api/events {
        proxy_pass http://127.0.0.1:8171;
        proxy_buffering off;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        proxy_pass http://127.0.0.1:8171;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```sh
ln -s /etc/nginx/sites-available/jumpscan /etc/nginx/sites-enabled/jumpscan
nginx -t && systemctl reload nginx
certbot --nginx -d jumpscan.example.com --redirect
```

Point the app at `https://jumpscan.example.com`. Release builds of the app only accept `https://`.

### Nginx Proxy Manager

1. Proxy Hosts -> Add Proxy Host.
2. Domain Names: `jumpscan.example.com`; Scheme `http`.
3. Forward Hostname/IP and Forward Port: if NPM shares a Docker network with JumpScan, use `jumpscan`
   and port `8000`. If NPM runs with host networking, use `127.0.0.1` and port `8171` (the port compose
   publishes). The container itself always listens on `8000`.
4. Enable "Block Common Exploits".
5. SSL tab: request a new Let's Encrypt certificate, enable Force SSL, HTTP/2 and HSTS.

NPM running in a normal container cannot reach the host's `127.0.0.1:8171`; use a shared Docker network
(`docker network connect`) and proxy to `jumpscan:8000`, or run NPM with host networking.

## 6. Backups

The whole state is one SQLite file. Copy it while the server is running, using SQLite's own backup call
so the copy is consistent:

```sh
docker compose exec -T jumpscan python -c "import sqlite3; s=sqlite3.connect('/data/jumpscan.db'); d=sqlite3.connect('/data/jumpscan-backup.db'); s.backup(d); d.close(); s.close()"
docker cp jumpscan:/data/jumpscan-backup.db ./jumpscan-$(date +%F).db
docker compose exec jumpscan rm /data/jumpscan-backup.db
sha256sum ./jumpscan-$(date +%F).db
```

Put that in a daily cron job and keep copies off the server. Restore:

```sh
docker compose stop
docker cp ./jumpscan-2026-11-20.db jumpscan:/data/jumpscan.db
docker compose exec -u root jumpscan chown jumpscan:jumpscan /data/jumpscan.db
docker compose start
```

## 7. Undo

```sh
cd /srv/jumpscan
docker compose down            # stop and remove the container, keep the data volume
# docker compose down -v       # also delete the volume: all users and jumps are gone
```

Then remove what the proxy added: delete the Proxy Host in NPM or the
`/etc/nginx/sites-available/jumpscan` symlink and reload nginx, remove the certificate
(`certbot delete --cert-name jumpscan.example.com`), and remove the DNS record. Deleting the cloned
directory finishes the job — nothing else on the host was touched.

## Notes and limits

- Single uvicorn process, no workers: fine for a club, and it keeps SQLite writes simple.
- The API is HTTP inside the host. Do not expose `8171` on a public interface; the compose file binds it
  to `127.0.0.1` on purpose.
- Login is rate limited to 5 failures per email and address pair and 30 failures per address per 15 minutes;
  request bodies are capped at 16 KB. The address limit needs `--proxy-headers --forwarded-allow-ips`
  (set in both startup paths using `FORWARDED_ALLOW_IPS`) to see through the reverse proxy.
- No video or image ever reaches the server, so there is nothing large to store and nothing to redact.

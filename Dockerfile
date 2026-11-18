# Server image only. The Android app and the Kotlin core are not built here.
FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    JUMPSCAN_DB=/data/jumpscan.db

# Unprivileged runtime user; the SQLite database lives in the /data volume.
RUN groupadd --system jumpscan \
    && useradd --system --gid jumpscan --home-dir /app --shell /usr/sbin/nologin jumpscan \
    && mkdir -p /data \
    && chown jumpscan:jumpscan /data

WORKDIR /app

# Dependencies first so the layer is reused when only server code changes.
COPY server/requirements.txt server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt

COPY server/ server/
RUN chown -R jumpscan:jumpscan /app

USER jumpscan

VOLUME ["/data"]
EXPOSE 8000

# GET / serves the coach dashboard, so a 200 here also proves the file shipped.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/', timeout=4)"

CMD ["sh", "-c", "exec uvicorn server.main:app --host 0.0.0.0 --port 8000 --no-server-header --proxy-headers --forwarded-allow-ips \"${FORWARDED_ALLOW_IPS:?Set FORWARDED_ALLOW_IPS to the trusted proxy address}\""]

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    STORY_DATA_DIR=/app/data \
    STORY_TRUST_PROXY=1 \
    PORT=8787

WORKDIR /app

# git is needed to install textgate from GitHub in requirements.txt
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /app/data \
    && chown -R appuser:appuser /app

USER appuser

EXPOSE 8787

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8787} --proxy-headers --forwarded-allow-ips=* --timeout-keep-alive 75"]

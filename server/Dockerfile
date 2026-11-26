# 이야기 서버 이미지. API 키는 이미지에 넣지 않고, 실행할 때 .env로 받습니다 (compose.yaml).
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    STORY_DATA_DIR=/data

# textgate는 GitHub 태그에서 받으므로 설치할 때만 git이 필요합니다
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app ./app
COPY tools ./tools

# root가 아닌 사용자로 실행. /data는 기록(episodes, reports)과 그림이 쌓이는 곳이라 볼륨으로 붙입니다
RUN useradd --system --uid 10001 --no-create-home story \
    && mkdir -p /data \
    && chown story:story /data
USER story
VOLUME ["/data"]

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8787/health', timeout=4)"

# 하루 생성 제한이 메모리에 있어서 프로세스는 하나만 띄웁니다 (--workers를 늘리지 않기).
# 앞의 Caddy가 붙여 주는 X-Forwarded-For로 실제 주소를 봐야 주소별 제한이 맞게 셉니다.
# 8787은 compose 안쪽 망에만 열려 있어서 Caddy 말고는 이 헤더를 보낼 수 없습니다.
# 접속 기록(access log)은 끕니다. 켜면 차마다 IP 주소가 docker 로그에 남습니다.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8787", \
     "--proxy-headers", "--forwarded-allow-ips", "*", "--no-server-header", "--no-access-log"]

# syntax=docker/dockerfile:1.7

ARG DOCKER_REGISTRY=docker.io
ARG PYTHON_VERSION=3.10.16
FROM ${DOCKER_REGISTRY}/library/python:${PYTHON_VERSION}-slim AS build
ARG PIP_INDEX_URL=https://pypi.org/simple
ARG UV_DEFAULT_INDEX=https://pypi.org/simple
ENV PIP_NO_CACHE_DIR=1
WORKDIR /app
RUN pip install --no-cache-dir uv==0.10.6
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project --no-cache

FROM ${DOCKER_REGISTRY}/library/python:${PYTHON_VERSION}-slim AS runtime
ARG NPM_CONFIG_REGISTRY=https://registry.npmjs.org
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    VIRTUAL_ENV=/app/.venv \
    PATH=/app/.venv/bin:$PATH \
    HOME=/home/sandbox \
    PORT=8061
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends bubblewrap socat ripgrep nodejs npm ca-certificates openjdk-17-jdk-headless \
    && npm install -g @anthropic-ai/sandbox-runtime@0.0.52 \
    && npm cache clean --force \
    && apt-mark manual bubblewrap socat ripgrep nodejs ca-certificates openjdk-17-jdk-headless \
    && apt-get purge -y --auto-remove npm \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /root/.npm /tmp/* \
    && groupadd --gid 10001 sandbox \
    && useradd --uid 10001 --gid sandbox --home-dir /home/sandbox --create-home --shell /usr/sbin/nologin sandbox
COPY --from=build /app/.venv ./.venv
COPY app ./app
COPY server.py ./
EXPOSE 8061
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8061/ready', timeout=3)"]
USER sandbox:sandbox
CMD ["python", "server.py"]

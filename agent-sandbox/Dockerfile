# syntax=docker/dockerfile:1.7

ARG DOCKER_REGISTRY=docker.io
ARG PYTHON_VERSION=3.10.16
FROM ${DOCKER_REGISTRY}/library/python:${PYTHON_VERSION}-slim AS build
ARG PIP_INDEX_URL=https://pypi.org/simple
ARG UV_DEFAULT_INDEX=https://pypi.org/simple
ENV UV_LINK_MODE=copy
WORKDIR /app
RUN --mount=type=cache,id=job-buddy-pip,target=/root/.cache/pip \
    pip install uv==0.10.6
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,id=job-buddy-uv,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-install-project

FROM ${DOCKER_REGISTRY}/library/python:${PYTHON_VERSION}-slim AS runtime
ARG NPM_CONFIG_REGISTRY=https://registry.npmjs.org
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    VIRTUAL_ENV=/app/.venv \
    PATH=/app/.venv/bin:$PATH \
    HOME=/home/sandbox \
    PORT=8061
WORKDIR /app
COPY --from=build /usr/local/bin/uv /usr/local/bin/uv
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
ENV AGENT_SANDBOX_DEPENDENCY_ROOT=/run/job-buddy-sandbox-deps \
    AGENT_SANDBOX_DEPENDENCY_CACHE_DIR=/var/cache/job-buddy-sandbox-uv
RUN mkdir -p /run/job-buddy-sandbox-deps /var/cache/job-buddy-sandbox-uv \
    && chown sandbox:sandbox /run/job-buddy-sandbox-deps /var/cache/job-buddy-sandbox-uv
COPY --from=build /app/.venv ./.venv
COPY app ./app
COPY server.py ./
EXPOSE 8061
HEALTHCHECK --interval=30s --timeout=20s --start-period=60s --retries=5 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8061/ready', timeout=18)"]
USER sandbox:sandbox
CMD ["python", "server.py"]

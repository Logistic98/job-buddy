# syntax=docker/dockerfile:1.7

FROM python:3.10-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Install OS dependencies required by Anthropic Sandbox Runtime on Linux.
# - bubblewrap: filesystem/process isolation backend
# - socat: proxy bridging
# - ripgrep: deny path detection used by sandbox-runtime
# - nodejs/npm: install the upstream `srt` CLI package
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bubblewrap \
        socat \
        ripgrep \
        nodejs \
        npm \
        curl \
        ca-certificates \
    && npm install -g @anthropic-ai/sandbox-runtime \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /root/.npm

COPY pyproject.toml README.md server.py ./
COPY app ./app
COPY tests ./tests

RUN pip install --upgrade pip \
    && pip install -e ".[dev]"

ENV PORT=8061

EXPOSE 8061

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8061/health || exit 1

CMD ["python", "server.py"]

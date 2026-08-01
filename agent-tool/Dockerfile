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
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    VIRTUAL_ENV=/app/.venv \
    PATH=/app/.venv/bin:$PATH \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
WORKDIR /app
COPY --from=build /app/.venv ./.venv
RUN python -m playwright install --with-deps --only-shell chromium \
    && rm -rf /var/lib/apt/lists/* /root/.cache /tmp/*
COPY app ./app
COPY server.py ./
EXPOSE 8040
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8040/health', timeout=3)"]
CMD ["python", "server.py"]

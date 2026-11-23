# syntax=docker/dockerfile:1.7
# Build from the repository root: docker build -f ai-service/Dockerfile .
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.11.31 /uv /bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never PYTHONUNBUFFERED=1
WORKDIR /app
COPY ai-service/pyproject.toml ai-service/uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev --no-install-project
COPY ai-service/ ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev \
    && useradd --system --uid 10001 --no-create-home app
# Parses untrusted PDFs (threat model T06): never as root; compose adds CPU and memory limits.
USER 10001:10001
ENV PATH=/app/.venv/bin:$PATH
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=10 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8081/health/ready').status == 200 else 1)"
# Migrations run with the ai schema owner role, then the app runs with the least-privileged role.
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port 8081 --no-server-header"]

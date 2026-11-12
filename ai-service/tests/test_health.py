import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, create_engine

from app.db import get_engine
from app.main import create_app


def test_liveness_does_not_depend_on_the_database() -> None:
    client = TestClient(create_app())

    response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_readiness_is_503_when_database_is_unreachable() -> None:
    app = create_app()
    # Port 1 is never a PostgreSQL server; connect_timeout keeps the test fast.
    unreachable = create_engine("postgresql+psycopg://nobody@127.0.0.1:1/none?connect_timeout=1")
    app.dependency_overrides[get_engine] = lambda: unreachable

    response = TestClient(app).get("/health/ready")

    assert response.status_code == 503
    assert response.json() == {"status": "DOWN", "checks": {"database": "DOWN"}}


@pytest.mark.db
def test_readiness_is_up_when_schema_is_migrated(app_engine: Engine) -> None:
    app = create_app()
    app.dependency_overrides[get_engine] = lambda: app_engine

    response = TestClient(app).get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "checks": {"database": "UP", "schema": "UP"}}

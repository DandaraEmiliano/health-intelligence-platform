import os
from collections.abc import Iterator
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import Engine, create_engine

from app.config import Settings

SERVICE_ROOT = Path(__file__).resolve().parent.parent


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    if os.environ.get("AI_RUN_DB_TESTS") == "1":
        return
    skip = pytest.mark.skip(reason="set AI_RUN_DB_TESTS=1 and AI_DB_* to run database tests")
    for item in items:
        if "db" in item.keywords:
            item.add_marker(skip)


@pytest.fixture(scope="session")
def settings() -> Settings:
    return Settings()


@pytest.fixture(scope="session")
def migrated(settings: Settings) -> Iterator[None]:
    """Apply all migrations as the owner role, and remove them afterwards."""
    config = Config(str(SERVICE_ROOT / "alembic.ini"))
    config.attributes["database_url"] = settings.owner_database_url()
    command.upgrade(config, "head")
    yield
    command.downgrade(config, "base")


@pytest.fixture(scope="session")
def app_engine(settings: Settings, migrated: None) -> Iterator[Engine]:
    engine = create_engine(settings.app_database_url())
    yield engine
    engine.dispose()

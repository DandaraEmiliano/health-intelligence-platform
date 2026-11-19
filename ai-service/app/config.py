"""Service configuration from environment variables (ADR 0008: no secrets in code)."""

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", env_file=None, extra="ignore")

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "hip"
    # Runtime uses the least-privileged role; migrations use the owner role (ADR 0005).
    db_app_user: str = "ai_app"
    db_app_password: SecretStr = Field(default=SecretStr(""), alias="AI_APP_PASSWORD")
    db_owner_user: str = "ai_owner"
    db_owner_password: SecretStr = Field(default=SecretStr(""), alias="AI_OWNER_PASSWORD")
    db_connect_timeout_seconds: int = 3

    # Queues and storage (ADR 0003, ADR 0012). Endpoints are set only for the local emulators.
    aws_region: str = Field(default="sa-east-1", alias="AWS_REGION")
    s3_endpoint: str | None = Field(default=None, alias="AWS_ENDPOINT_URL_S3")
    sqs_endpoint: str | None = Field(default=None, alias="AWS_ENDPOINT_URL_SQS")
    extraction_requests_queue: str = "document-extraction-requests"
    extraction_results_queue: str = "document-extraction-results"
    # Receives after which a transient failure is reported as FAILED instead of retried
    # (the queue's maxReceiveCount is 5; reporting on the last one keeps it out of the DLQ).
    extraction_max_receives: int = 5

    # Extraction worker and LLM provider (ADR 0008). The API key comes from ANTHROPIC_API_KEY.
    extraction_worker_enabled: bool = True
    llm_provider: str = "anthropic"
    llm_model: str = "claude-opus-5-5"
    llm_effort: str = "high"

    def app_database_url(self) -> str:
        return self._url(self.db_app_user, self.db_app_password)

    def owner_database_url(self) -> str:
        return self._url(self.db_owner_user, self.db_owner_password)

    def _url(self, user: str, password: SecretStr) -> str:
        from sqlalchemy import URL

        return URL.create(
            "postgresql+psycopg",
            username=user,
            password=password.get_secret_value() or None,
            host=self.db_host,
            port=self.db_port,
            database=self.db_name,
            query={"connect_timeout": str(self.db_connect_timeout_seconds)},
        ).render_as_string(hide_password=False)


@lru_cache
def get_settings() -> Settings:
    return Settings()

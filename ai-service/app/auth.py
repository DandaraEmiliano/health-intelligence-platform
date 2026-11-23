"""Bearer tokens for internal endpoints (ADR 0002, threat model T08).

Only Keycloak-issued client-credentials tokens with audience `ai-service` from an allowed client
(core-api) are accepted. Signing keys come from the realm's JWKS and are cached.
"""

from collections.abc import Callable
from functools import lru_cache
from typing import Annotated

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import Settings, get_settings

_bearer = HTTPBearer(auto_error=False)


@lru_cache
def _jwks_client(url: str) -> jwt.PyJWKClient:
    return jwt.PyJWKClient(url, cache_keys=True, lifespan=600, timeout=3)


def get_key_resolver(settings: Annotated[Settings, Depends(get_settings)]) -> Callable[[str], object]:
    """Signing key for a token, from the realm's JWKS. Tests override this dependency."""
    url = settings.oidc_jwks_url or f"{settings.oidc_issuer}/protocol/openid-connect/certs"
    return lambda token: _jwks_client(url).get_signing_key_from_jwt(token).key


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(status.HTTP_401_UNAUTHORIZED, detail=detail, headers={"WWW-Authenticate": "Bearer"})


def require_service_token(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
    settings: Annotated[Settings, Depends(get_settings)],
    resolve: Annotated[Callable[[str], object], Depends(get_key_resolver)],
) -> dict:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise _unauthorized("missing bearer token")
    try:
        claims = jwt.decode(
            credentials.credentials,
            key=resolve(credentials.credentials),
            algorithms=["RS256"],
            audience=settings.oidc_audience,
            issuer=settings.oidc_issuer,
            options={"require": ["exp", "iat", "iss", "aud"]},
        )
    except (jwt.PyJWTError, jwt.PyJWKClientError) as e:
        raise _unauthorized(type(e).__name__) from e
    if claims.get("azp") not in settings.allowed_client_ids:
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail="client not allowed")
    return claims

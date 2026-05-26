"""
httpx wrappers for every Spring Boot REST endpoint that agents need.

All tools are async and return JSON strings so the LLM can read the results.
Base URL is read from TRANSFORM_API_URL (default: http://localhost:8080).
An optional TRANSFORM_API_TOKEN env var is forwarded as a Bearer token.
"""

import json
import os

import httpx
from langchain_core.tools import tool

_TRANSFORM_API_URL = os.getenv("TRANSFORM_API_URL", "http://localhost:8080")
_TRANSFORM_API_TOKEN = os.getenv("TRANSFORM_API_TOKEN", "")


def _headers() -> dict:
    h: dict = {"Content-Type": "application/json", "Accept": "application/json"}
    if _TRANSFORM_API_TOKEN:
        h["Authorization"] = f"Bearer {_TRANSFORM_API_TOKEN}"
    return h


def _raise_with_body(resp: httpx.Response) -> None:
    """Raise an error that includes the response body so the LLM can self-correct."""
    if not resp.is_error:
        return
    try:
        body = resp.json()
    except Exception:
        body = resp.text
    raise ValueError(f"HTTP {resp.status_code} from {resp.url}: {json.dumps(body)}")


# ── File Specs ────────────────────────────────────────────────────────────────

@tool
async def list_file_specs(format: str = "") -> str:
    """List all file specs registered on the platform.
    Optionally filter by format: CSV, FIXED_WIDTH, XML, JSON, NACHA, ISO20022, SWIFT_MT, DELIMITED, CUSTOM."""
    params: dict = {}
    if format:
        params["format"] = format.upper()
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{_TRANSFORM_API_URL}/api/v1/specs",
            params=params,
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def get_file_spec(spec_id: str) -> str:
    """Get the full details of a file spec by its ID."""
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{_TRANSFORM_API_URL}/api/v1/specs/{spec_id}",
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def create_file_spec(spec_json: str) -> str:
    """Create a new file spec.
    spec_json must be a JSON string with: name (str), format (FileFormat enum),
    fields (list of FieldSpec), and optionally description, delimiter, hasHeader,
    validationRules, correctionRules."""
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            f"{_TRANSFORM_API_URL}/api/v1/specs",
            content=spec_json,
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


# ── Service Integrations ───────────────────────────────────────────────────────

@tool
async def list_integrations() -> str:
    """List all service integrations (SFTP, FTP, S3, Kafka)."""
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{_TRANSFORM_API_URL}/api/v1/integrations",
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def get_integration(integration_id: str) -> str:
    """Get the details of a specific integration by ID."""
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{_TRANSFORM_API_URL}/api/v1/integrations/{integration_id}",
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def create_integration(integration_json: str) -> str:
    """Create a new service integration.

    integration_json must be a JSON string with ALL of these required fields:
      type (str): SFTP | FTP | S3 | KAFKA
      userId (str): owner identifier, e.g. "system"
      shortDescription (str): human-readable label
      updatedBy (str): who is creating this, e.g. "ai-assistant"
      details (object): connection-specific map

    SFTP example:
    {
      "type": "SFTP", "userId": "system", "updatedBy": "ai-assistant",
      "shortDescription": "Bank SFTP drop",
      "details": {"host": "sftp.bank.com", "port": 22, "username": "user",
                  "password": "secret", "remoteDir": "/outbox"}
    }

    KAFKA example:
    {
      "type": "KAFKA", "userId": "system", "updatedBy": "ai-assistant",
      "shortDescription": "Bank transactions topic",
      "details": {"bootstrapServers": "localhost:9092", "topic": "bank-transactions"}
    }
    """
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            f"{_TRANSFORM_API_URL}/api/v1/integrations",
            content=integration_json,
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def update_integration(integration_id: str, update_json: str) -> str:
    """Update an existing service integration's description or connection details.

    integration_id: the ID of the integration to update.
    update_json must be a JSON string with at least updatedBy and optionally:
      shortDescription (str): new human-readable label
      details (object): updated connection-specific map (same keys as create)

    Example — change SFTP password:
    {
      "shortDescription": "Bank SFTP drop (updated)",
      "updatedBy": "ai-assistant",
      "details": {"host": "sftp.bank.com", "port": 22, "userName": "user",
                  "password": "new-secret", "directories": ["/outbox"]}
    }

    Example — update description only:
    {
      "shortDescription": "Renamed integration",
      "updatedBy": "ai-assistant"
    }
    """
    async with httpx.AsyncClient() as client:
        resp = await client.put(
            f"{_TRANSFORM_API_URL}/api/v1/integrations/{integration_id}",
            content=update_json,
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def enable_integration(integration_id: str) -> str:
    """Enable a service integration to start polling for files."""
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            f"{_TRANSFORM_API_URL}/api/v1/integrations/{integration_id}/enable",
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def disable_integration(integration_id: str) -> str:
    """Disable a service integration to pause file polling."""
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            f"{_TRANSFORM_API_URL}/api/v1/integrations/{integration_id}/disable",
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


# ── Profiles ──────────────────────────────────────────────────────────────────

@tool
async def list_profiles(status: str = "") -> str:
    """List all processing profiles. Optionally filter by status: DRAFT, ENABLED, DISABLED."""
    params: dict = {}
    if status:
        params["status"] = status.upper()
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{_TRANSFORM_API_URL}/api/profiles",
            params=params,
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def get_profile(profile_id: str) -> str:
    """Get the full details of a processing profile by ID."""
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{_TRANSFORM_API_URL}/api/profiles/{profile_id}",
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def create_profile(profile_json: str) -> str:
    """Create a new processing profile.

    profile_json must be a JSON string matching CreateProfileRequest.
    Required fields: name (str), clientId (str), windowConfig (object).
    windowConfig requires openTrigger and closeTrigger — each must include "type".

    Minimal working example (TIME_BASED open/close, no actions):
    {
      "name": "my-pipeline",
      "clientId": "default",
      "description": "Daily bank file processing",
      "windowConfig": {
        "openTrigger":  {"type": "TIME_BASED", "openCron": "0 6 * * 1-5"},
        "closeTrigger": {"type": "TIME_BASED", "openCron": "0 6 * * 1-5", "windowDuration": "PT1H"}
      },
      "actions": []
    }

    WindowTrigger "type" values: TIME_BASED | FILE_ARRIVAL | EVENT_COUNT | SESSION_GAP | COMPOUND | MANUAL
    """
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            f"{_TRANSFORM_API_URL}/api/profiles",
            content=profile_json,
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def enable_profile(profile_id: str) -> str:
    """Enable a profile (DRAFT|DISABLED → ENABLED)."""
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            f"{_TRANSFORM_API_URL}/api/profiles/{profile_id}/enable",
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


# ── Executions ────────────────────────────────────────────────────────────────

@tool
async def list_executions(status: str = "", profile_id: str = "") -> str:
    """List workflow executions.
    Optionally filter by status (PENDING, RUNNING, COMPLETED, FAILED) or profile_id."""
    params: dict = {}
    if status:
        params["status"] = status.upper()
    async with httpx.AsyncClient() as client:
        if profile_id:
            url = f"{_TRANSFORM_API_URL}/api/profiles/{profile_id}/executions"
        else:
            url = f"{_TRANSFORM_API_URL}/api/executions"
        resp = await client.get(url, params=params, headers=_headers(), timeout=15)
        _raise_with_body(resp)
        return json.dumps(resp.json())


@tool
async def get_execution(execution_id: str) -> str:
    """Get the full details of a workflow execution including all step results."""
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{_TRANSFORM_API_URL}/api/executions/{execution_id}",
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())


# ── Windows ───────────────────────────────────────────────────────────────────

@tool
async def list_windows(status: str = "", profile_id: str = "") -> str:
    """List scheduling windows. Optionally filter by status or profile_id."""
    params: dict = {}
    if status:
        params["status"] = status.upper()
    if profile_id:
        params["profileId"] = profile_id
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{_TRANSFORM_API_URL}/api/windows",
            params=params,
            headers=_headers(),
            timeout=15,
        )
        _raise_with_body(resp)
        return json.dumps(resp.json())

"""
Helpers for extracting structured data from LangChain ToolMessages.

Each agent node calls extract_tool_results() on the sub-agent's message list,
then calls build_structured_payload() to pick the highest-priority renderable
data for the frontend.
"""

import json
from typing import Any


# ── ToolMessage extraction ─────────────────────────────────────────────────────

def extract_tool_results(messages: list) -> dict[str, Any]:
    """Scan a sub-agent message list and return {tool_name: parsed_json} for
    every ToolMessage whose content is valid JSON."""
    results: dict[str, Any] = {}
    for msg in messages:
        if getattr(msg, "type", None) == "tool" or type(msg).__name__ == "ToolMessage":
            tool_name = getattr(msg, "name", None)
            content = getattr(msg, "content", "")
            if tool_name and content:
                try:
                    results[tool_name] = json.loads(content)
                except (json.JSONDecodeError, TypeError):
                    pass
    return results


# ── Priority tables per agent ──────────────────────────────────────────────────

_INSIGHTS_PRIORITY = [
    "calculate_metrics",
    "get_error_summary",
    "list_executions",
    "list_windows",
    "list_profiles",
]

_GENERAL_PRIORITY = [
    "list_profiles",
    "list_file_specs",
    "list_executions",
    "list_windows",
]

_CREATE_PRIORITY = [
    ("create_profile", "profile"),
    ("create_file_spec", "file_spec"),
    ("create_integration", "integration"),
]

_UPDATE_PRIORITY = [
    ("update_integration", "integration"),
]


# ── Payload builders ───────────────────────────────────────────────────────────

def _profile_list_payload(data: Any) -> dict:
    items = data if isinstance(data, list) else []
    return {
        "type": "profile_list",
        "items": [
            {"id": p.get("id"), "name": p.get("name") or p.get("description"),
             "status": p.get("status"), "clientId": p.get("clientId")}
            for p in items[:20]
        ],
        "total": len(items),
    }


def _file_spec_list_payload(data: Any) -> dict:
    items = data if isinstance(data, list) else []
    return {
        "type": "file_spec_list",
        "items": [
            {"id": s.get("id"), "name": s.get("name"),
             "format": s.get("format"), "fieldCount": len(s.get("fields", []))}
            for s in items[:20]
        ],
        "total": len(items),
    }


def _execution_list_payload(data: Any) -> dict:
    items = data if isinstance(data, list) else []
    return {
        "type": "execution_list",
        "items": [
            {"id": e.get("id"), "status": e.get("status"),
             "profileId": e.get("profileId"),
             "totalRecords": e.get("totalRecordsProcessed"),
             "startedAt": e.get("startedAt") or e.get("createdAt")}
            for e in items[:20]
        ],
        "total": len(items),
    }


def _window_list_payload(data: Any) -> dict:
    items = data if isinstance(data, list) else []
    return {
        "type": "window_list",
        "items": [
            {"id": w.get("id"), "status": w.get("status"),
             "profileId": w.get("profileId"),
             "openedAt": w.get("openedAt"), "closedAt": w.get("closedAt")}
            for w in items[:20]
        ],
        "total": len(items),
    }


def _metrics_payload(data: dict) -> dict:
    return {"type": "metrics_summary", **data}


def _error_summary_payload(data: dict) -> dict:
    return {"type": "error_summary", **data}


def _resource_created_payload(data: Any, resource_type: str) -> dict | None:
    if not isinstance(data, dict):
        return None
    return {
        "type": "resource_created",
        "resource_type": resource_type,
        "id": data.get("id"),
        "name": data.get("name") or data.get("description") or data.get("shortDescription"),
        "status": data.get("status"),
    }


def _resource_updated_payload(data: Any, resource_type: str) -> dict | None:
    if not isinstance(data, dict):
        return None
    return {
        "type": "resource_updated",
        "resource_type": resource_type,
        "id": data.get("id"),
        "name": data.get("name") or data.get("description") or data.get("shortDescription"),
        "status": data.get("status"),
    }


# ── Public API ─────────────────────────────────────────────────────────────────

def build_structured_payload(
    tool_results: dict[str, Any],
    agent_type: str,
) -> dict | None:
    """Return the highest-priority structured payload for the given agent type,
    or None if nothing renderable was found."""

    if not tool_results:
        return None

    # All agents: check for updated resources first
    for tool_name, resource_type in _UPDATE_PRIORITY:
        if tool_name in tool_results:
            payload = _resource_updated_payload(tool_results[tool_name], resource_type)
            if payload:
                return payload

    # Creation agents: check for created resources
    if agent_type in ("onboarding", "flow_builder", "general"):
        for tool_name, resource_type in _CREATE_PRIORITY:
            if tool_name in tool_results:
                payload = _resource_created_payload(tool_results[tool_name], resource_type)
                if payload:
                    return payload

    # Insights agent: metrics → error summary → executions → windows → profiles
    if agent_type == "insights":
        for tool_name in _INSIGHTS_PRIORITY:
            if tool_name not in tool_results:
                continue
            data = tool_results[tool_name]
            if tool_name == "calculate_metrics":
                return _metrics_payload(data)
            if tool_name == "get_error_summary":
                return _error_summary_payload(data)
            if tool_name == "list_executions":
                return _execution_list_payload(data)
            if tool_name == "list_windows":
                return _window_list_payload(data)
            if tool_name == "list_profiles":
                return _profile_list_payload(data)

    # General agent (and fallback for onboarding/flow_builder after creation check):
    # profiles → specs → executions → windows
    for tool_name in _GENERAL_PRIORITY:
        if tool_name not in tool_results:
            continue
        data = tool_results[tool_name]
        if tool_name == "list_profiles":
            return _profile_list_payload(data)
        if tool_name == "list_file_specs":
            return _file_spec_list_payload(data)
        if tool_name == "list_executions":
            return _execution_list_payload(data)
        if tool_name == "list_windows":
            return _window_list_payload(data)

    return None

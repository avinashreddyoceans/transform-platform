"""
Python-side analytics tools that aggregate data from the Transform Platform API.

These tools make direct httpx calls and perform aggregations locally so the LLM
receives a pre-digested summary rather than raw paginated results.
"""

import json
import os
from datetime import datetime, timedelta, timezone

import httpx
from langchain_core.tools import tool

_TRANSFORM_API_URL = os.getenv("TRANSFORM_API_URL", "http://localhost:8080")
_TRANSFORM_API_TOKEN = os.getenv("TRANSFORM_API_TOKEN", "")


def _headers() -> dict:
    h: dict = {"Content-Type": "application/json", "Accept": "application/json"}
    if _TRANSFORM_API_TOKEN:
        h["Authorization"] = f"Bearer {_TRANSFORM_API_TOKEN}"
    return h


def _parse_ts(ts_str: str | None) -> datetime | None:
    if not ts_str:
        return None
    try:
        return datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None


@tool
async def get_error_summary(profile_id: str = "", days: int = 7) -> str:
    """Aggregate and summarize errors from recent failed workflow executions.
    Returns total failed count, top error types, and affected profile breakdown.
    Use profile_id to scope to one profile; leave empty for platform-wide summary."""
    async with httpx.AsyncClient() as client:
        url = (
            f"{_TRANSFORM_API_URL}/api/profiles/{profile_id}/executions"
            if profile_id
            else f"{_TRANSFORM_API_URL}/api/executions"
        )
        resp = await client.get(url, headers=_headers(), timeout=15)
        resp.raise_for_status()
        all_executions: list = resp.json() if isinstance(resp.json(), list) else []

    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    failed = []
    for ex in all_executions:
        ts = _parse_ts(ex.get("lastUpdatedAt") or ex.get("completedAt"))
        if (ts is None or ts >= cutoff) and ex.get("status") == "FAILED":
            failed.append(ex)

    error_counts: dict[str, int] = {}
    for ex in failed:
        msg = ex.get("errorMessage") or "Unknown error"
        # Normalise to a short error category
        lower = msg.lower()
        if "timeout" in lower:
            key = "Timeout"
        elif "validation" in lower:
            key = "Validation failure"
        elif "connection" in lower or "connect" in lower:
            key = "Connection error"
        elif "not found" in lower or "404" in lower:
            key = "Resource not found"
        elif "permission" in lower or "403" in lower or "401" in lower:
            key = "Permission denied"
        else:
            key = msg[:80].strip()
        error_counts[key] = error_counts.get(key, 0) + 1

    top_errors = sorted(error_counts.items(), key=lambda x: -x[1])[:5]

    return json.dumps({
        "period_days": days,
        "total_failed_executions": len(failed),
        "top_errors": [{"error": e, "count": c} for e, c in top_errors],
        "profile_id_filter": profile_id or "all",
    })


@tool
async def calculate_metrics(profile_id: str = "", days: int = 7) -> str:
    """Calculate processing throughput, success rate, and average duration metrics
    for recent workflow executions. Use profile_id to scope to one profile;
    leave empty for platform-wide metrics."""
    async with httpx.AsyncClient() as client:
        url = (
            f"{_TRANSFORM_API_URL}/api/profiles/{profile_id}/executions"
            if profile_id
            else f"{_TRANSFORM_API_URL}/api/executions"
        )
        resp = await client.get(url, headers=_headers(), timeout=15)
        resp.raise_for_status()
        all_executions: list = resp.json() if isinstance(resp.json(), list) else []

    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    recent = []
    for ex in all_executions:
        ts = _parse_ts(ex.get("lastUpdatedAt") or ex.get("completedAt"))
        if ts is None or ts >= cutoff:
            recent.append(ex)

    total = len(recent)
    if total == 0:
        return json.dumps({
            "period_days": days,
            "total_executions": 0,
            "message": "No executions found in the specified period.",
            "profile_id_filter": profile_id or "all",
        })

    completed = [e for e in recent if e.get("status") == "COMPLETED"]
    failed = [e for e in recent if e.get("status") == "FAILED"]
    running = [e for e in recent if e.get("status") == "RUNNING"]

    durations = [e["durationMs"] for e in recent if e.get("durationMs")]
    avg_duration_ms = sum(durations) / len(durations) if durations else 0

    total_records = sum(e.get("totalRecordsProcessed", 0) for e in completed)

    return json.dumps({
        "period_days": days,
        "total_executions": total,
        "completed": len(completed),
        "failed": len(failed),
        "running": len(running),
        "success_rate_pct": round(len(completed) / total * 100, 1),
        "avg_duration_ms": round(avg_duration_ms),
        "total_records_processed": total_records,
        "throughput_records_per_day": round(total_records / days, 1),
        "profile_id_filter": profile_id or "all",
    })

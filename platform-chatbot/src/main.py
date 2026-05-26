"""
FastAPI application — session management and chat endpoint.

Routes:
  POST   /sessions                  → create session, return session_id
  POST   /sessions/{id}/chat        → send message, get agent response
  DELETE /sessions/{id}             → delete session
  GET    /health                    → liveness check

Sessions are stored in an in-memory dict and expire after SESSION_TTL_MINUTES
(default: 120) of inactivity.  Stale sessions are cleaned up on each request.
"""

import os
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone

from dotenv import load_dotenv

load_dotenv()  # must run before importing graph (agents read env vars at import time)

from typing import Any

from fastapi import FastAPI, HTTPException
from langchain_core.messages import HumanMessage
from pydantic import BaseModel

from src.graph import get_graph

# ── Session store ─────────────────────────────────────────────────────────────

_SESSION_TTL = timedelta(minutes=int(os.getenv("SESSION_TTL_MINUTES", "120")))
_sessions: dict[str, dict] = {}


def _prune_sessions() -> None:
    cutoff = datetime.now(timezone.utc) - _SESSION_TTL
    stale = [sid for sid, s in _sessions.items() if s["last_activity"] < cutoff]
    for sid in stale:
        del _sessions[sid]


def _load_session(session_id: str) -> dict:
    _prune_sessions()
    session = _sessions.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Session not found or expired")
    return session


# ── FastAPI app ───────────────────────────────────────────────────────────────

@asynccontextmanager
async def _lifespan(app: FastAPI):
    get_graph()  # warm up: build the graph and instantiate LLM singletons
    yield


app = FastAPI(
    title="Platform Chatbot",
    description="LangGraph multi-agent chatbot for Transform Platform",
    version="1.0.0",
    lifespan=_lifespan,
)


# ── Schemas ───────────────────────────────────────────────────────────────────

class SessionResponse(BaseModel):
    session_id: str
    created_at: str


class ChatRequest(BaseModel):
    message: str


class ChatResponse(BaseModel):
    response: str
    active_agent: str
    wizard_step: int
    wizard_total: int
    tools_used: list[str]
    structured_data: dict[str, Any] | None = None
    updated_at: str


class HealthResponse(BaseModel):
    status: str
    model: str
    transform_api_url: str


# ── Routes ────────────────────────────────────────────────────────────────────

@app.post("/sessions", response_model=SessionResponse, status_code=201)
async def create_session() -> SessionResponse:
    """Create a new chat session and return its ID."""
    session_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc)

    _sessions[session_id] = {
        "state": {
            "messages": [],
            "session_id": session_id,
            "active_agent": "general",
            "wizard_step": 0,
            "wizard_data": {},
            "agent_metadata": {},
        },
        "last_activity": now,
    }

    return SessionResponse(session_id=session_id, created_at=now.isoformat())


@app.post("/sessions/{session_id}/chat", response_model=ChatResponse)
async def chat(session_id: str, req: ChatRequest) -> ChatResponse:
    """Send a message to the multi-agent graph and get a response."""
    session = _load_session(session_id)
    state: dict = session["state"]

    # Append the new user message
    state["messages"] = list(state.get("messages", [])) + [
        HumanMessage(content=req.message)
    ]

    graph = get_graph()
    try:
        result = await graph.ainvoke(state)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Agent error: {exc}") from exc

    # Persist updated state
    now = datetime.now(timezone.utc)
    session["state"] = result
    session["last_activity"] = now

    # Extract response text from the last message
    messages = result.get("messages", [])
    last_msg = messages[-1] if messages else None
    response_text: str = (
        last_msg.content if last_msg and hasattr(last_msg, "content") else ""
    )

    meta: dict = result.get("agent_metadata", {})

    return ChatResponse(
        response=response_text,
        active_agent=result.get("active_agent", "general"),
        wizard_step=result.get("wizard_step", 0),
        wizard_total=meta.get("wizard_total", 5),
        tools_used=meta.get("tools_used", []),
        structured_data=meta.get("structured_data"),
        updated_at=now.isoformat(),
    )


@app.delete("/sessions/{session_id}", status_code=204)
async def delete_session(session_id: str) -> None:
    """Delete a chat session and free its memory."""
    _sessions.pop(session_id, None)


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Liveness check — returns model and downstream API URL."""
    return HealthResponse(
        status="ok",
        model=os.getenv("AI_MODEL", "claude-sonnet-4-6"),
        transform_api_url=os.getenv("TRANSFORM_API_URL", "http://localhost:8080"),
    )

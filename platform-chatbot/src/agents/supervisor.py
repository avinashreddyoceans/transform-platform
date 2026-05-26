"""
Supervisor node — classifies the user's intent and sets active_agent.

Uses a minimal claude-sonnet-4-6 call (max_tokens=20) to classify the last
user message into one of four routing targets.  The routing function in
graph.py reads active_agent and dispatches to the correct sub-agent node.
"""

import os

from langchain_anthropic import ChatAnthropic
from langchain_core.messages import SystemMessage

_SUPERVISOR_SYSTEM = """\
You are a routing supervisor for the Transform Platform AI assistant.
Your only task is to output EXACTLY ONE of these routing labels — nothing else:

  onboarding     user wants to get started, set up a pipeline for the first time,
                 or be guided step by step ("help me set up", "onboard", "new pipeline",
                 "first time", "get started", "walk me through").

  flow_builder   user wants to build or configure a FileSpec, define fields,
                 add correction or validation rules, or configure a processing profile
                 ("file spec", "field mapping", "correction rule", "validation rule",
                 "build a flow", "define fields", "create spec").

  insights       user asks about errors, metrics, throughput, performance, SLAs,
                 or wants to analyse trends ("failed executions", "error summary",
                 "how many records", "success rate", "slow", "metrics", "trend").

  general        everything else — listing resources, status checks, general questions.

Respond with ONLY the label word, lowercase, no punctuation."""

_llm: ChatAnthropic | None = None


def _get_llm() -> ChatAnthropic:
    global _llm
    if _llm is None:
        _llm = ChatAnthropic(
            model=os.getenv("AI_MODEL", "claude-sonnet-4-6"),
            max_tokens=20,
            temperature=0,
        )
    return _llm


_VALID_AGENTS = {"onboarding", "flow_builder", "insights", "general"}


async def supervisor_node(state: dict) -> dict:
    """Classify intent and update active_agent so the graph can route correctly."""
    messages = state.get("messages", [])
    if not messages:
        return {"active_agent": "general"}

    last = messages[-1]
    user_text: str = last.content if hasattr(last, "content") else str(last)

    llm = _get_llm()
    response = await llm.ainvoke(
        [
            SystemMessage(content=_SUPERVISOR_SYSTEM),
            {"role": "user", "content": user_text},
        ]
    )

    intent = response.content.strip().lower().split()[0] if response.content.strip() else "general"
    if intent not in _VALID_AGENTS:
        intent = "general"

    return {"active_agent": intent}

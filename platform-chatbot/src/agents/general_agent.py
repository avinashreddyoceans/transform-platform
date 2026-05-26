"""
General agent — fallback for status checks, resource listing, and miscellaneous questions.

Handles everything that doesn't fit the onboarding, flow builder, or insights categories.
"""

import os

from langchain_anthropic import ChatAnthropic
from langgraph.prebuilt import create_react_agent

from src.tools.platform_api import (
    disable_integration,
    enable_integration,
    enable_profile,
    get_execution,
    get_file_spec,
    get_integration,
    get_profile,
    list_executions,
    list_file_specs,
    list_integrations,
    list_profiles,
    list_windows,
)

_SYSTEM_PROMPT = """\
You are the Transform Platform Assistant — a knowledgeable, concise helper for the
Transform Platform file processing system.

You can answer questions about the current state of the platform:
- File specs: formats, field definitions, validation rules
- Service integrations: SFTP, S3, FTP, Kafka connectors and their status
- Processing profiles: configuration, status, trigger schedules
- Scheduling windows: open/closed state, associated profiles
- Workflow executions: status, record counts, recent activity

Guidelines:
- Use tools to fetch live data rather than speculating.
- Keep answers concise. Use bullet lists for multiple items.
- If the user's request belongs to onboarding, spec building, or analytics,
  acknowledge the topic but answer what you can with the available tools.
- For destructive actions (delete, disable) always ask for confirmation first.
"""

_agent_app = None


def _get_app():
    global _agent_app
    if _agent_app is None:
        llm = ChatAnthropic(
            model=os.getenv("AI_MODEL", "claude-sonnet-4-6"),
            max_tokens=int(os.getenv("AI_MAX_TOKENS", "2048")),
        )
        tools = [
            list_file_specs,
            get_file_spec,
            list_integrations,
            get_integration,
            enable_integration,
            disable_integration,
            list_profiles,
            get_profile,
            enable_profile,
            list_windows,
            list_executions,
            get_execution,
        ]
        _agent_app = create_react_agent(llm, tools=tools, prompt=_SYSTEM_PROMPT)
    return _agent_app


async def general_node(state: dict) -> dict:
    app = _get_app()
    result = await app.ainvoke({"messages": state["messages"]})

    final_msg = result["messages"][-1]

    tools_used: list[str] = []
    for msg in result["messages"]:
        if hasattr(msg, "tool_calls") and msg.tool_calls:
            for tc in msg.tool_calls:
                name = tc.get("name") if isinstance(tc, dict) else getattr(tc, "name", None)
                if name and name not in tools_used:
                    tools_used.append(name)

    return {
        "messages": [final_msg],
        "active_agent": "general",
        "wizard_step": 0,
        "agent_metadata": {"tools_used": tools_used},
    }

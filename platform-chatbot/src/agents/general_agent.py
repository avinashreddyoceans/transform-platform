"""
General agent — fallback for status checks, resource listing, and miscellaneous questions.

Handles everything that doesn't fit the onboarding, flow builder, or insights categories.
"""

import os

from langchain_anthropic import ChatAnthropic
from langgraph.prebuilt import create_react_agent

from src.tools.structured_data import build_structured_payload, extract_tool_results
from src.tools.platform_api import (
    create_file_spec,
    create_integration,
    create_profile,
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
    update_integration,
)

_SYSTEM_PROMPT = """\
You are the Transform Platform Assistant — a knowledgeable, concise helper for the
Transform Platform file processing system.

You can answer questions about and manage the platform:
- File specs: list, fetch, or create specs with fields, validation, and correction rules
- Service integrations: SFTP, S3, FTP, Kafka connectors — list, create, update, enable, disable
- Processing profiles: list, create, enable profiles with window configurations
- Scheduling windows: open/closed state, associated profiles
- Workflow executions: status, record counts, recent activity

Guidelines:
- Use tools to fetch live data rather than speculating.
- Keep answers concise. Use bullet lists for multiple items.
- When creating a file spec, always confirm the format (CSV, FIXED_WIDTH, XML, etc.) and field list first.
- When creating a profile, confirm the name, clientId, and trigger type before calling create_profile.
- When updating an integration, fetch it first with get_integration to show the user the current values.
- For destructive actions (delete, disable) always ask for confirmation first.
- updatedBy should always be set to "ai-assistant" for operations you perform.
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
            create_file_spec,
            list_integrations,
            get_integration,
            create_integration,
            update_integration,
            enable_integration,
            disable_integration,
            list_profiles,
            get_profile,
            create_profile,
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

    tool_results = extract_tool_results(result["messages"])
    structured_data = build_structured_payload(tool_results, "general")

    return {
        "messages": [final_msg],
        "active_agent": "general",
        "wizard_step": 0,
        "agent_metadata": {"tools_used": tools_used, "structured_data": structured_data},
    }

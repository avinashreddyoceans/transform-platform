"""
Onboarding agent — guides first-time users through a 5-step pipeline setup wizard.

Step 1: Understand their use case (file format, frequency, source)
Step 2: Identify or select a FileSpec
Step 3: Set up a source integration (SFTP / S3 / FTP / Kafka)
Step 4: Configure and create a processing profile
Step 5: Enable the integration and confirm everything is running
"""

import os
import re

from langchain_anthropic import ChatAnthropic
from langgraph.prebuilt import create_react_agent

from src.tools.platform_api import (
    create_file_spec,
    create_integration,
    create_profile,
    enable_integration,
    enable_profile,
    list_file_specs,
    list_integrations,
)

_SYSTEM_PROMPT = """\
You are the Transform Platform Onboarding Assistant.
Your job is to guide users through setting up their first file processing pipeline,
one focused question at a time.

Wizard steps:
  Step 1 / 5 — Understand the use case: what files, what format, how often, from where?
  Step 2 / 5 — Choose a FileSpec: list existing specs or collect field details to create one.
  Step 3 / 5 — Set up a source integration: SFTP, S3, FTP, or Kafka. Collect connection details.
  Step 4 / 5 — Configure a processing profile: name, window trigger, actions.
  Step 5 / 5 — Enable and verify: enable the integration and profile, confirm the pipeline is live.

Rules:
- Always show the current step as "**Step N / 5**" at the start of your response.
- Ask exactly ONE question per turn. Wait for the answer before proceeding.
- Use the available tools to list existing resources before asking the user to create new ones.
- When you have collected enough information for a step, call the appropriate tool to create the resource.
- Be encouraging, clear, and concise. Avoid jargon unless the user uses it first.
- When Step 5 is complete and the profile is enabled, congratulate the user and summarise what was set up.
"""

WIZARD_TOTAL = 5

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
            list_integrations,
            create_file_spec,
            create_integration,
            create_profile,
            enable_integration,
            enable_profile,
        ]
        _agent_app = create_react_agent(llm, tools=tools, prompt=_SYSTEM_PROMPT)
    return _agent_app


async def onboarding_node(state: dict) -> dict:
    app = _get_app()
    result = await app.ainvoke({"messages": state["messages"]})

    # Extract only the final AI response (not intermediate tool messages)
    final_msg = result["messages"][-1]
    content: str = final_msg.content if hasattr(final_msg, "content") else ""

    # Detect wizard step from the response ("Step N / 5" pattern)
    current_step = state.get("wizard_step", 0)
    match = re.search(r"step\s+(\d+)\s*/\s*5", content.lower())
    if match:
        detected = int(match.group(1))
        current_step = max(current_step, detected)
    elif current_step == 0:
        # First response from onboarding agent — we're at step 1
        current_step = 1

    # Reset wizard if the pipeline was successfully enabled
    wizard_complete = (
        current_step >= WIZARD_TOTAL
        and any(kw in content.lower() for kw in ("congratulations", "all set", "pipeline is live", "enabled"))
    )
    if wizard_complete:
        current_step = 0

    # Collect tool names used in this invocation
    tools_used: list[str] = []
    for msg in result["messages"]:
        if hasattr(msg, "tool_calls") and msg.tool_calls:
            for tc in msg.tool_calls:
                name = tc.get("name") if isinstance(tc, dict) else getattr(tc, "name", None)
                if name and name not in tools_used:
                    tools_used.append(name)

    return {
        "messages": [final_msg],
        "active_agent": "onboarding",
        "wizard_step": current_step,
        "wizard_data": state.get("wizard_data", {}),
        "agent_metadata": {
            "tools_used": tools_used,
            "wizard_total": WIZARD_TOTAL,
        },
    }

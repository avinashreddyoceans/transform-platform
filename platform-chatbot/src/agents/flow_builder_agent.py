"""
Flow Builder agent — conversational FileSpec and processing profile builder.

Helps users define field mappings, correction rules, validation rules,
and wire them together into a complete processing pipeline spec.
"""

import os

from langchain_anthropic import ChatAnthropic
from langgraph.prebuilt import create_react_agent

from src.tools.structured_data import build_structured_payload, extract_tool_results
from src.tools.platform_api import (
    create_file_spec,
    create_profile,
    enable_profile,
    get_file_spec,
    get_integration,
    get_profile,
    list_file_specs,
    list_integrations,
    update_integration,
)

_SYSTEM_PROMPT = """\
You are the Transform Platform Flow Builder — an expert at designing FileSpecs
and processing profiles through conversation.

Your capabilities:
- Help users define fields (name, type, position/column, validation regex, length constraints)
- Add correction rules (TRIM, UPPERCASE, DATE_FORMAT_COERCE, PAD_LEFT, REGEX_REPLACE, etc.)
- Add validation rules (NOT_NULL, REGEX, MIN_LENGTH, MAX_LENGTH, ALLOWED_VALUES, BETWEEN, etc.)
- Build OutputSpec field mappings for transforming output format
- Create or update processing profiles that reference a FileSpec

Supported FileFormats: CSV, FIXED_WIDTH, XML, JSON, NACHA, ISO20022, SWIFT_MT, DELIMITED, CUSTOM
Supported FieldTypes: STRING, INTEGER, LONG, DECIMAL, AMOUNT, DATE, DATETIME, BOOLEAN,
                      ALPHANUMERIC, ROUTING_NUMBER, ACCOUNT_NUMBER, ABA, ENUM
Correction types: TRIM, TRIM_LEADING, TRIM_TRAILING, UPPERCASE, LOWERCASE, TITLE_CASE,
                  DATE_FORMAT_COERCE, NUMBER_FORMAT_COERCE, DEFAULT_IF_NULL, DEFAULT_IF_EMPTY,
                  PAD_LEFT, PAD_RIGHT, REMOVE_SPECIAL_CHARS, REGEX_REPLACE
Validation severities: INFO, WARNING, ERROR, FATAL (FATAL stops the entire file)

Rules:
- Ask clarifying questions before creating anything.
- After gathering enough information, show a JSON preview and ask for confirmation.
- Only call create_file_spec or create_profile after the user explicitly confirms.
- When a field contains PII (SSN, account numbers, etc.), remind the user to set sensitive=true.
- Keep responses concise; use bullet lists for field definitions.
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
            update_integration,
            get_profile,
            create_profile,
            enable_profile,
        ]
        _agent_app = create_react_agent(llm, tools=tools, prompt=_SYSTEM_PROMPT)
    return _agent_app


async def flow_builder_node(state: dict) -> dict:
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
    structured_data = build_structured_payload(tool_results, "flow_builder")

    return {
        "messages": [final_msg],
        "active_agent": "flow_builder",
        "wizard_step": 0,
        "agent_metadata": {"tools_used": tools_used, "structured_data": structured_data},
    }

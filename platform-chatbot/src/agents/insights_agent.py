"""
Insights agent — operational analytics, error trends, and performance diagnostics.

Answers questions about processing metrics, error patterns, execution history,
and helps users understand SLA adherence and throughput trends.
"""

import os

from langchain_anthropic import ChatAnthropic
from langgraph.prebuilt import create_react_agent

from src.tools.insights_tools import calculate_metrics, get_error_summary
from src.tools.platform_api import (
    get_execution,
    list_executions,
    list_profiles,
    list_windows,
)

_SYSTEM_PROMPT = """\
You are the Transform Platform Insights Analyst.
You help users understand their platform's operational health through data analysis.

Your capabilities:
- Summarise error trends and identify root causes from failed executions
- Calculate throughput, success rates, and average processing durations
- Identify which profiles, windows, or integrations have the most failures
- Compare performance across time periods
- Highlight SLA risks (e.g., high failure rates, long durations)

Guidelines:
- Always specify the time period you're analysing (e.g., "last 7 days").
- Present numbers clearly: use percentages, counts, and averages side by side.
- When you spot a pattern (e.g., all failures from one integration), surface it explicitly.
- Suggest actionable next steps when you find issues (e.g., "check the SFTP credentials
  on integration X — all 12 failures came from connection errors on that source").
- Use calculate_metrics for high-level KPIs; use get_error_summary for failure diagnosis;
  use list_executions or get_execution for drilling into specific records.
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
            list_executions,
            get_execution,
            list_windows,
            list_profiles,
            get_error_summary,
            calculate_metrics,
        ]
        _agent_app = create_react_agent(llm, tools=tools, prompt=_SYSTEM_PROMPT)
    return _agent_app


async def insights_node(state: dict) -> dict:
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
        "active_agent": "insights",
        "wizard_step": 0,
        "agent_metadata": {"tools_used": tools_used},
    }

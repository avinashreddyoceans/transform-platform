from typing import Annotated, TypedDict

from langgraph.graph.message import add_messages


class ChatState(TypedDict):
    messages: Annotated[list, add_messages]
    session_id: str
    active_agent: str   # "onboarding" | "flow_builder" | "insights" | "general"
    wizard_step: int    # 0 = not in wizard; 1-N = wizard in progress
    wizard_data: dict   # collected wizard answers
    agent_metadata: dict  # passed back to UI (tools_used, wizard_total, badge)

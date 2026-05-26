"""
LangGraph StateGraph wiring.

Topology:
  START → supervisor → [onboarding | flow_builder | insights | general] → END

The supervisor node classifies intent and sets state["active_agent"].
The conditional edge reads that field to dispatch to the correct sub-agent.
Each sub-agent runs its own internal ReAct loop via create_react_agent,
then emits only the final AI message back to the main graph state.
"""

from langgraph.graph import END, START, StateGraph

from src.agents.flow_builder_agent import flow_builder_node
from src.agents.general_agent import general_node
from src.agents.insights_agent import insights_node
from src.agents.onboarding_agent import onboarding_node
from src.agents.supervisor import supervisor_node
from src.state import ChatState

_SUB_AGENTS = ("onboarding", "flow_builder", "insights", "general")


def _route(state: dict) -> str:
    agent = state.get("active_agent", "general")
    return agent if agent in _SUB_AGENTS else "general"


def build_graph():
    graph = StateGraph(ChatState)

    graph.add_node("supervisor", supervisor_node)
    graph.add_node("onboarding", onboarding_node)
    graph.add_node("flow_builder", flow_builder_node)
    graph.add_node("insights", insights_node)
    graph.add_node("general", general_node)

    graph.add_edge(START, "supervisor")

    graph.add_conditional_edges(
        "supervisor",
        _route,
        {agent: agent for agent in _SUB_AGENTS},
    )

    for agent in _SUB_AGENTS:
        graph.add_edge(agent, END)

    return graph.compile()


# Module-level singleton — built once on first import.
_graph = None


def get_graph():
    global _graph
    if _graph is None:
        _graph = build_graph()
    return _graph

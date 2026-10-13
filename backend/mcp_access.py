"""Stable MCP tool scope policy.

Changing or adding an MCP tool requires an explicit scope decision here.
Unregistered tools and malformed tool calls fail closed instead of obtaining
implicit read privileges. A write grant is not automatically a read grant.
"""
from __future__ import annotations

READ_TOOLS = frozenset({
    "list_recent_transactions",
    "spending_summary",
    "get_subscription_list",
    "get_recurring_expense_candidates",
    "personal_finance_long_term_trends",
    "finance_reconciliation_queue",
    "finance_duplicate_review",
})
WRITE_TOOLS = frozenset({
    "add_subscription",
    "edit_record",
    "register_merchant_rule",
    "link_refund",
})
TOOL_SCOPES = {
    **{name: "finance:read" for name in READ_TOOLS},
    **{name: "finance:write" for name in WRITE_TOOLS},
}


class InvalidMCPCall(ValueError):
    """Malformed or unsupported MCP request."""


def required_scope(payload: object) -> str | None:
    """Return scope needed for this JSON-RPC payload.

    Non-tool-call MCP protocol messages require a valid OAuth token, but no
    data scope. A tool call requires its explicit scope. Array/batched
    JSON-RPC is disallowed to prevent smuggling writes past the auth gate.
    """
    if not isinstance(payload, dict):
        raise InvalidMCPCall("MCP expects one JSON-RPC object per request")
    method = payload.get("method")
    if not isinstance(method, str):
        raise InvalidMCPCall("MCP method is required")
    if method != "tools/call":
        return None
    params = payload.get("params")
    if not isinstance(params, dict):
        raise InvalidMCPCall("tools/call requires object params")
    name = params.get("name")
    if not isinstance(name, str) or name not in TOOL_SCOPES:
        raise InvalidMCPCall("MCP tool is not registered in access policy")
    return TOOL_SCOPES[name]

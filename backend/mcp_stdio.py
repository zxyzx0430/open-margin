"""Local, read-only MCP stdio process for an explicitly configured AI client."""
from __future__ import annotations

import asyncio

from mcp_access import READ_TOOLS, WRITE_TOOLS
from mcp_service import mcp


def prepare_read_only() -> None:
    names = {tool.name for tool in asyncio.run(mcp.list_tools())}
    expected = READ_TOOLS | WRITE_TOOLS
    if names != expected:
        raise RuntimeError("MCP policy mismatch: refusing unknown tool definitions")
    for name in WRITE_TOOLS:
        mcp.remove_tool(name)


def main() -> None:
    prepare_read_only()
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()

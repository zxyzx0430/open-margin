"""Local stdio MCP must expose only read tools; isolated synthetic data."""
import asyncio
import os
import tempfile
import unittest
from pathlib import Path

from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client
from mcp_access import READ_TOOLS, WRITE_TOOLS


class LocalStdioMCPTests(unittest.TestCase):
    def test_stdio_read_only_and_query(self):
        async def exercise():
            with tempfile.TemporaryDirectory(prefix="om-readonly-stdio-") as temp:
                env = {
                    **os.environ,
                    "FINANCE_DB": str(Path(temp) / "finance.sqlite3"),
                    "FINANCE_OAUTH_DB": str(Path(temp) / "oauth.sqlite3"),
                    "FINANCE_WALLOS_DB": str(Path(temp) / "wallos.sqlite3"),
                    "FINANCE_PUBLIC_URL": "https://finance.example.com/finance",
                }
                params = StdioServerParameters(
                    command=os.sys.executable,
                    args=["mcp_stdio.py"],
                    cwd=str(Path(__file__).parent),
                    env=env,
                )
                async with stdio_client(params) as (read, write):
                    async with ClientSession(read, write) as client:
                        await client.initialize()
                        registered = {tool.name for tool in (await client.list_tools()).tools}
                        self.assertEqual(registered, READ_TOOLS)
                        self.assertFalse(registered.intersection(WRITE_TOOLS))
                        result = await client.call_tool("list_recent_transactions", {"limit": 2})
                        self.assertFalse(result.isError)
                        denied = await client.call_tool("edit_record", {"tx_id": 1, "merchant": "do-not-write"})
                        self.assertTrue(denied.isError)
        asyncio.run(exercise())


if __name__ == "__main__":
    unittest.main()

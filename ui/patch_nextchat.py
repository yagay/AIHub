#!/usr/bin/env python3
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()

def edit(rel, pairs):
    path = root / rel
    text = path.read_text(encoding="utf-8")
    for old, new in pairs:
        if old not in text:
            raise SystemExit(f"NextChat marker changed in {rel}: {old}")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")

edit("app/store/access.ts", [
    ('useCustomConfig: false,', 'useCustomConfig: true,'),
    ('openaiUrl: DEFAULT_OPENAI_URL,', 'openaiUrl: "http://127.0.0.1:3847",'),
    ('needCode: true,', 'needCode: false,'),
    ('hideUserApiKey: false,', 'hideUserApiKey: true,'),
])
edit("app/store/config.ts", [
    ('model: "gpt-4o-mini" as ModelType,', 'model: "chatgpt-web" as ModelType,'),
    ('enableAutoGenerateTitle: true,', 'enableAutoGenerateTitle: false,'),
])
edit("app/client/platforms/openai.ts", [
    ('  private disableListModels = true;', '  private disableListModels = false;'),
    ('    const chatModels = resJson.data?.filter(\n      (m) => m.id.startsWith("gpt-") || m.id.startsWith("chatgpt-"),\n    );', '    const chatModels = resJson.data;'),
])
edit("next.config.mjs", [
    ('  output: mode,', '  output: mode,\n  assetPrefix: mode === "export" ? "." : undefined,\n  eslint: { ignoreDuringBuilds: true },'),
])

# Static Android build: server-side MCP is intentionally disabled. Keep the
# exported function call signatures permissive because client components still
# type-check calls such as addMcpServer(id, config) even when MCP is disabled.
(root / "app/mcp/actions.ts").write_text('''export async function getClientsStatus(..._args: any[]){return {}}\nexport async function getClientTools(..._args: any[]){return null}\nexport async function getAvailableClientsCount(..._args: any[]){return 0}\nexport async function getAllTools(..._args: any[]){return []}\nexport async function initializeMcpSystem(..._args: any[]){return {mcpServers:{}}}\nexport async function addMcpServer(..._args: any[]){return {mcpServers:{}}}\nexport async function pauseMcpServer(..._args: any[]){return {mcpServers:{}}}\nexport async function resumeMcpServer(..._args: any[]): Promise<void>{}\nexport async function removeMcpServer(..._args: any[]){return {mcpServers:{}}}\nexport async function restartAllClients(..._args: any[]){return {mcpServers:{}}}\nexport async function executeMcpAction(..._args: any[]): Promise<never>{throw new Error("MCP disabled in AIHub Android build")}\nexport async function getMcpConfigFromFile(..._args: any[]){return {mcpServers:{}}}\nexport async function isMcpEnabled(..._args: any[]){return false}\n''', encoding='utf-8')
print('Patched NextChat for AIHub Titanium local broker')

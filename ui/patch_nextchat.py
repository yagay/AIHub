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

# Existing AIHub installs already have NextChat access settings persisted in
# WebView localStorage. Defaults alone do not override those values. Bump the
# persisted store version and migrate only transport-related fields so chat
# history and unrelated UI preferences remain untouched.
edit("app/store/access.ts", [
    ('    version: 2,', '    version: 3,'),
    ('      return persistedState as any;\n    },',
     '      if (version < 3) {\n'
     '        const state = persistedState as any;\n'
     '        state.useCustomConfig = true;\n'
     '        state.openaiUrl = "http://127.0.0.1:3847";\n'
     '        state.openaiApiKey = "";\n'
     '        state.provider = ServiceProvider.OpenAI;\n'
     '        state.needCode = false;\n'
     '        state.hideUserApiKey = true;\n'
     '      }\n\n'
     '      return persistedState as any;\n'
     '    },'),
])

edit("app/store/config.ts", [
    ('model: "gpt-4o-mini" as ModelType,', 'model: "chatgpt-web" as ModelType,'),
    ('enableAutoGenerateTitle: true,', 'enableAutoGenerateTitle: false,'),
])

# Migrate the persisted global model selection as well. Individual old chats can
# still contain legacy gpt-* model names; LocalBroker handles those as a safe
# ChatGPT-Web compatibility fallback.
edit("app/store/config.ts", [
    ('    version: 4.1,', '    version: 4.2,'),
    ('      return state as any;\n    },',
     '      if (version < 4.2) {\n'
     '        state.modelConfig.model = "chatgpt-web" as ModelType;\n'
     '        state.modelConfig.providerName = "OpenAI" as ServiceProvider;\n'
     '        state.enableAutoGenerateTitle = false;\n'
     '      }\n\n'
     '      return state as any;\n'
     '    },'),
])

edit("app/client/platforms/openai.ts", [
    ('  private disableListModels = true;', '  private disableListModels = false;'),
    ('    const chatModels = resJson.data?.filter(\n      (m) => m.id.startsWith("gpt-") || m.id.startsWith("chatgpt-"),\n    );', '    const chatModels = resJson.data;'),
])
edit("next.config.mjs", [
    ('  output: mode,', '  output: mode,\n  assetPrefix: mode === "export" ? "." : undefined,\n  eslint: { ignoreDuringBuilds: true },\n  // Android ships only the static client; server-only MCP/realtime type checks are irrelevant.\n  typescript: { ignoreBuildErrors: true },'),
])

# Static Android build: server-side MCP is intentionally disabled. Keep the
# exported function call signatures permissive because client components still
# reference them even though MCP cannot be enabled in this build.
(root / "app/mcp/actions.ts").write_text('''export async function getClientsStatus(..._args: any[]): Promise<Record<string, any>>{return {}}\nexport async function getClientTools(..._args: any[]): Promise<any>{return null}\nexport async function getAvailableClientsCount(..._args: any[]): Promise<number>{return 0}\nexport async function getAllTools(..._args: any[]): Promise<any[]>{return []}\nexport async function initializeMcpSystem(..._args: any[]): Promise<any>{return {mcpServers:{}}}\nexport async function addMcpServer(..._args: any[]): Promise<any>{return {mcpServers:{}}}\nexport async function pauseMcpServer(..._args: any[]): Promise<any>{return {mcpServers:{}}}\nexport async function resumeMcpServer(..._args: any[]): Promise<void>{}\nexport async function removeMcpServer(..._args: any[]): Promise<any>{return {mcpServers:{}}}\nexport async function restartAllClients(..._args: any[]): Promise<any>{return {mcpServers:{}}}\nexport async function executeMcpAction(..._args: any[]): Promise<never>{throw new Error("MCP disabled in AIHub Android build")}\nexport async function getMcpConfigFromFile(..._args: any[]): Promise<any>{return {mcpServers:{}}}\nexport async function isMcpEnabled(..._args: any[]): Promise<boolean>{return false}\n''', encoding='utf-8')
print('Patched NextChat for AIHub Titanium local broker')

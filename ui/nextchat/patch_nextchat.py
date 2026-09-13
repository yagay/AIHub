#!/usr/bin/env python3
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()


def edit(rel, fn):
    path = root / rel
    text = path.read_text(encoding="utf-8")
    updated = fn(text)
    if updated == text:
        raise SystemExit(f"patch produced no change: {rel}")
    path.write_text(updated, encoding="utf-8")


def patch_config(text: str) -> str:
    replacements = {
        'enableAutoGenerateTitle: true,': 'enableAutoGenerateTitle: false,',
        'model: "gpt-4o-mini" as ModelType,': 'model: "gpt-4" as ModelType,',
        'sendMemory: true,': 'sendMemory: false,',
        'historyMessageCount: 4,': 'historyMessageCount: 20,',
        'compressMessageLengthThreshold: 1000,': 'compressMessageLengthThreshold: 1000000,',
    }
    for old, new in replacements.items():
        if old not in text:
            raise SystemExit(f"NextChat config marker changed: {old}")
        text = text.replace(old, new, 1)
    return text


def patch_access(text: str) -> str:
    replacements = {
        'useCustomConfig: false,': 'useCustomConfig: true,',
        'openaiUrl: DEFAULT_OPENAI_URL,': 'openaiUrl: "http://127.0.0.1:3456",',
        'needCode: true,': 'needCode: false,',
        'hideUserApiKey: false,': 'hideUserApiKey: true,',
    }
    for old, new in replacements.items():
        if old not in text:
            raise SystemExit(f"NextChat access marker changed: {old}")
        text = text.replace(old, new, 1)
    return text


def patch_openai(text: str) -> str:
    marker = '  private disableListModels = true;'
    if marker not in text:
        raise SystemExit("NextChat OpenAI model-list marker changed")
    text = text.replace(marker, '  private disableListModels = false;', 1)

    old = '''    const chatModels = resJson.data?.filter(\n      (m) => m.id.startsWith("gpt-") || m.id.startsWith("chatgpt-"),\n    );'''
    new = '''    // AIHub's local browser gateway already exposes only authorized web models.\n    // Keep every model so Claude/Gemini/DeepSeek/Grok appear in the same selector.\n    const chatModels = resJson.data;'''
    if old not in text:
        raise SystemExit("NextChat OpenAI model filter marker changed")
    return text.replace(old, new, 1)


def patch_settings(text: str) -> str:
    import_marker = 'import { ModelConfigList } from "./model-config";'
    if import_marker not in text:
        raise SystemExit("NextChat settings import marker changed")
    text = text.replace(
        import_marker,
        import_marker + '\nimport { AIHubGatewaySettings } from "./aihub-gateway-settings";',
        1,
    )

    list_marker = '<List id={SlotID.CustomModel}>'
    if list_marker not in text:
        raise SystemExit("NextChat settings custom-model marker changed")
    text = text.replace(
        list_marker,
        list_marker + '\n          <AIHubGatewaySettings />',
        1,
    )

    # The mobile build uses browser sessions only; remove API/SaaS onboarding from this section.
    text = text.replace('          {saasStartComponent}\n', '', 1)
    text = text.replace('          {accessCodeComponent}\n', '', 1)
    return text


def patch_next_config(text: str) -> str:
    marker = '  output: mode,'
    if marker not in text:
        raise SystemExit("NextChat next.config output marker changed")
    return text.replace(
        marker,
        marker
        + '\n  // Android WebView serves the export below /assets/ui; keep chunks relative.'
        + '\n  assetPrefix: mode === "export" ? "." : undefined,'
        + '\n  // Upstream lint currently crashes on an unused-imports rule under the CI Node version.'
        + '\n  eslint: { ignoreDuringBuilds: true },',
        1,
    )


def disable_server_mcp() -> None:
    # NextChat's MCP implementation uses Next.js Server Actions and cannot be statically
    # exported into an Android WebView. AIHub will add mobile MCP through the native/local
    # runtime later; keep the UI imports buildable with client-safe no-op shims for now.
    path = root / "app/mcp/actions.ts"
    path.write_text(
        '''export async function getClientsStatus() { return {}; }\n'''
        '''export async function getClientTools(_clientId: string) { return null; }\n'''
        '''export async function getAvailableClientsCount() { return 0; }\n'''
        '''export async function getAllTools() { return []; }\n'''
        '''export async function initializeMcpSystem() { return { mcpServers: {} }; }\n'''
        '''export async function addMcpServer(_clientId: string, _config: unknown) { return { mcpServers: {} }; }\n'''
        '''export async function pauseMcpServer(_clientId: string) { return { mcpServers: {} }; }\n'''
        '''export async function resumeMcpServer(_clientId: string) {}\n'''
        '''export async function removeMcpServer(_clientId: string) { return { mcpServers: {} }; }\n'''
        '''export async function restartAllClients() { return { mcpServers: {} }; }\n'''
        '''export async function executeMcpAction(_clientId: string, _request: unknown): Promise<never> {\n'''
        '''  throw new Error("MCP server actions are disabled in the Android static build");\n'''
        '''}\n'''
        '''export async function getMcpConfigFromFile() { return { mcpServers: {} }; }\n'''
        '''export async function isMcpEnabled() { return false; }\n''',
        encoding="utf-8",
    )


edit("app/store/config.ts", patch_config)
edit("app/store/access.ts", patch_access)
edit("app/client/platforms/openai.ts", patch_openai)
edit("app/components/settings.tsx", patch_settings)
edit("next.config.mjs", patch_next_config)
disable_server_mcp()
print("Patched NextChat for AIHub local OpenAI-compatible browser gateway")

import http, { IncomingMessage, ServerResponse } from "node:http";
import { BrowserManager } from "./src/browser/manager.ts";
import {
  clearProviderCache,
  getClientForModel,
  listAllModels,
  listProviderDefinitions,
  resolveModelToProvider,
} from "./src/providers/registry.ts";
import {
  listAuthorizedProviders,
  saveCredentials,
} from "./src/providers/auth-store.ts";
import {
  handleChatCompletions,
  setRouteTimeoutSec,
} from "./src/openai/chat-completions.ts";

const HOST = "127.0.0.1";
const PORT = Number.parseInt(process.env.TFG_PORT ?? "3456", 10);
const REQUEST_TIMEOUT_SEC = Number.parseInt(
  process.env.TFG_REQUEST_TIMEOUT_SEC ?? "300",
  10,
);

setRouteTimeoutSec(REQUEST_TIMEOUT_SEC);

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Access-Control-Allow-Private-Network": "true",
  "Cache-Control": "no-store",
};

function writeJson(res: ServerResponse, status: number, body: unknown) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    ...CORS_HEADERS,
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(text),
  });
  res.end(text);
}

async function readJson(req: IncomingMessage): Promise<any> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  const text = Buffer.concat(chunks).toString("utf8");
  return text ? JSON.parse(text) : {};
}

async function pipeWebResponse(res: ServerResponse, upstream: Response) {
  const headers: Record<string, string> = { ...CORS_HEADERS };
  upstream.headers.forEach((value, key) => {
    headers[key] = value;
  });
  res.writeHead(upstream.status, headers);
  if (!upstream.body) {
    res.end();
    return;
  }
  const reader = upstream.body.getReader();
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (value) res.write(Buffer.from(value));
    }
  } finally {
    res.end();
    reader.releaseLock();
  }
}

async function handle(req: IncomingMessage, res: ServerResponse) {
  const method = req.method ?? "GET";
  const url = new URL(req.url ?? "/", `http://${HOST}:${PORT}`);

  if (method === "OPTIONS") {
    res.writeHead(204, CORS_HEADERS);
    res.end();
    return;
  }

  if (method === "GET" && (url.pathname === "/health" || url.pathname === "/healthz")) {
    const browserHealthy = await BrowserManager.getInstance().isHealthy();
    const authorized = listAuthorizedProviders();
    writeJson(res, browserHealthy ? 200 : 503, {
      status: browserHealthy ? "ok" : "browser_disconnected",
      browser: browserHealthy ? "connected" : "disconnected",
      authorized,
      models: (await listAllModels()).length,
    });
    return;
  }

  if (method === "GET" && url.pathname === "/v1/models") {
    const now = Math.floor(Date.now() / 1000);
    const models = await listAllModels();
    const data = await Promise.all(
      models.map(async (model) => ({
        id: model.id,
        object: "model",
        created: now,
        owned_by: (await resolveModelToProvider(model.id)) ?? "web-provider",
      })),
    );
    writeJson(res, 200, { object: "list", data });
    return;
  }

  if (method === "POST" && url.pathname === "/v1/chat/completions") {
    const body = await readJson(req);
    const client = await getClientForModel(body.model ?? "");
    if (!client) {
      writeJson(res, 404, {
        error: {
          type: "invalid_request_error",
          message: `No authorized web provider for model ${body.model ?? ""}`,
        },
      });
      return;
    }
    await pipeWebResponse(res, await handleChatCompletions(body, client));
    return;
  }

  if (method === "GET" && url.pathname === "/aihub/providers") {
    const authorized = new Set(listAuthorizedProviders());
    const definitions = await listProviderDefinitions();
    writeJson(
      res,
      200,
      definitions.map((definition) => ({
        id: definition.id,
        name: definition.name,
        authorized: authorized.has(definition.id),
        models: definition.models,
      })),
    );
    return;
  }

  const authMatch = /^\/aihub\/auth\/([^/]+)$/.exec(url.pathname);
  if (method === "POST" && authMatch) {
    const providerId = decodeURIComponent(authMatch[1]!);
    const definitions = await listProviderDefinitions();
    const definition = definitions.find((item) => item.id === providerId);
    if (!definition) {
      writeJson(res, 404, { error: `Unknown provider: ${providerId}` });
      return;
    }

    console.log(`[AIHub] Starting browser authorization for ${definition.name}`);
    const credentials = await definition.loginFn({
      onProgress: (message) => console.log(`[AIHub][${providerId}] ${message}`),
      openUrl: async () => true,
    });
    saveCredentials(providerId, credentials);
    clearProviderCache(providerId);
    writeJson(res, 200, { ok: true, providerId });
    return;
  }

  writeJson(res, 404, { error: `Unknown endpoint: ${method} ${url.pathname}` });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    console.error("[AIHub] gateway request failed", error);
    if (!res.headersSent) {
      writeJson(res, 500, {
        error: error instanceof Error ? error.message : String(error),
      });
    } else {
      res.end();
    }
  });
});

server.listen(PORT, HOST, () => {
  console.log(`[AIHub] embedded browser gateway listening on http://${HOST}:${PORT}`);
});

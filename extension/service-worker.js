const BROKER = "ws://127.0.0.1:3847/bridge";
const WORKER_MARKER = "AIHUB_BRIDGE_WORKER_V7";
const PROVIDER_URLS = [
  "*://chatgpt.com/*",
  "*://chat.openai.com/*",
  "*://claude.ai/*",
  "*://gemini.google.com/*",
  "*://chat.deepseek.com/*",
  "*://grok.com/*",
  "*://www.grok.com/*",
];

const ports = new Map();
const repairedTabs = new Set();
let socket = null;
let reconnectTimer = null;
let reconnectDelay = 500;
let pingTimer = null;

function providerFromUrl(url = "") {
  try {
    const host = new URL(url).hostname.toLowerCase();
    if (host === "chatgpt.com" || host === "chat.openai.com") return "chatgpt";
    if (host === "claude.ai") return "claude";
    if (host === "gemini.google.com") return "gemini";
    if (host === "chat.deepseek.com") return "deepseek";
    if (host === "grok.com" || host === "www.grok.com") return "grok";
  } catch (_) {}
  return null;
}

function providerConnected(provider) {
  return !!(provider && ports.get(provider)?.size > 0);
}

function availableProviders() {
  return [...ports.keys()].filter((key) => ports.get(key)?.size > 0);
}

function send(message) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(message));
  return true;
}

function hello(reason = "state") {
  send({
    type: "hello",
    providers: availableProviders(),
    version: chrome.runtime.getManifest().version,
    worker: WORKER_MARKER,
    reason,
  });
}

function announceProviders() {
  if (!send({ type: "providers", providers: availableProviders() })) {
    connectBroker();
  }
}

function injectCurrentContentScript(tabId) {
  if (!Number.isInteger(tabId)) return;
  try {
    chrome.scripting.executeScript(
      { target: { tabId }, files: ["content.js"] },
      () => { void chrome.runtime.lastError; }
    );
  } catch (_) {}
}

// External-extension upgrades can leave already-open provider tabs running an old
// isolated-world content script. Prometheus intentionally uses a page-local
// __PROMETHEUS_LOADED__ guard, so simply injecting the new file into that stale tab
// would immediately return. Reload each disconnected provider tab at most once per
// worker lifetime, then explicitly inject current content.js after the reload finishes.
function repairProviderTab(tab, reason = "repair") {
  const tabId = tab?.id;
  const provider = providerFromUrl(tab?.url || "");
  if (!Number.isInteger(tabId) || !provider || providerConnected(provider)) return;
  if (repairedTabs.has(tabId)) return;
  repairedTabs.add(tabId);
  try {
    chrome.tabs.reload(tabId, {}, () => { void chrome.runtime.lastError; });
  } catch (_) {}
}

function repairOpenProviderTabs(reason = "scan") {
  try {
    chrome.tabs.query({ url: PROVIDER_URLS }, (tabs) => {
      void chrome.runtime.lastError;
      for (const tab of tabs || []) repairProviderTab(tab, reason);
    });
  } catch (_) {}
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  const delay = reconnectDelay;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectBroker();
  }, delay);
  reconnectDelay = Math.min(reconnectDelay * 2, 10000);
}

function stopPing() {
  if (pingTimer) clearInterval(pingTimer);
  pingTimer = null;
}

function connectBroker() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;

  try {
    socket = new WebSocket(BROKER);
  } catch (_) {
    socket = null;
    scheduleReconnect();
    return;
  }

  socket.onopen = () => {
    reconnectDelay = 500;
    hello("socket-open");
    repairOpenProviderTabs("socket-open");
    stopPing();
    pingTimer = setInterval(() => {
      if (!send({ type: "ping" })) connectBroker();
    }, 20000);
  };

  socket.onmessage = (event) => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }

    if (!message || !["ask", "command"].includes(message.type) || !message.id || !message.provider) return;

    const set = ports.get(message.provider);
    const list = set ? [...set] : [];
    const port = list.length ? list[list.length - 1] : null;
    if (!port) {
      repairOpenProviderTabs("command-without-provider");
      send({
        type: "result",
        id: message.id,
        response: "",
        error: `No active ${message.provider} tab in Titanium`,
      });
      return;
    }

    try {
      port.postMessage({ ...message, type: "command" });
    } catch (error) {
      send({
        type: "result",
        id: message.id,
        response: "",
        error: String(error?.message || error),
      });
    }
  };

  socket.onerror = () => {};
  socket.onclose = () => {
    stopPing();
    socket = null;
    scheduleReconnect();
  };
}

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== "aihub-provider") return;
  let provider = null;
  const senderTabId = port.sender?.tab?.id;

  port.onMessage.addListener((message) => {
    if (message?.type === "hello" && message.provider) {
      provider = message.provider;
      if (!ports.has(provider)) ports.set(provider, new Set());
      ports.get(provider).add(port);
      if (Number.isInteger(senderTabId)) repairedTabs.delete(senderTabId);
      connectBroker();
      hello("provider-connected");
      return;
    }

    if (message?.type === "progress" && message.id) {
      connectBroker();
      send({
        type: "progress",
        id: message.id,
        stage: message.stage || "progress",
        phase: message.phase || "",
        textLength: Number(message.textLength || 0),
        containerCount: Number(message.containerCount || 0),
      });
      return;
    }

    if (message?.type === "result" && message.id) {
      connectBroker();
      send({
        type: "result",
        id: message.id,
        response: message.response || "",
        error: message.error || "",
      });
    }
  });

  port.onDisconnect.addListener(() => {
    if (!provider) return;
    ports.get(provider)?.delete(port);
    if (ports.get(provider)?.size === 0) ports.delete(provider);
    announceProviders();
  });
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status !== "complete") return;
  const provider = providerFromUrl(tab?.url || "");
  if (!provider) return;
  // Fresh navigation clears Prometheus' page-local guard. Explicit injection here
  // is a safe fallback if Chromium skipped the declarative content-script injection.
  injectCurrentContentScript(tabId);
});

chrome.tabs.onActivated.addListener(({ tabId }) => {
  try {
    chrome.tabs.get(tabId, (tab) => {
      void chrome.runtime.lastError;
      repairProviderTab(tab, "activated");
    });
  } catch (_) {}
});

chrome.runtime.onStartup.addListener(() => {
  connectBroker();
  repairOpenProviderTabs("startup");
});
chrome.runtime.onInstalled.addListener(() => {
  connectBroker();
  repairOpenProviderTabs("installed");
});
connectBroker();
repairOpenProviderTabs("worker-load");

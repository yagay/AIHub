const BROKER = "ws://127.0.0.1:3847/bridge";
const WORKER_MARKER = "AIHUB_BRIDGE_WORKER_V4";
const ports = new Map();
let socket = null;
let reconnectTimer = null;
let reconnectDelay = 500;
let pingTimer = null;

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
    stopPing();
    pingTimer = setInterval(() => {
      if (!send({ type: "ping" })) connectBroker();
    }, 20000);
  };

  socket.onmessage = (event) => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }

    // LocalBroker historically emitted type:"ask" while the extension/content
    // side expected type:"command". Accept both at the WebSocket boundary and
    // normalize before forwarding to the provider content script.
    if (!message || !["ask", "command"].includes(message.type) || !message.id || !message.provider) return;

    const set = ports.get(message.provider);
    const list = set ? [...set] : [];
    const port = list.length ? list[list.length - 1] : null;
    if (!port) {
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

  port.onMessage.addListener((message) => {
    if (message?.type === "hello" && message.provider) {
      provider = message.provider;
      if (!ports.has(provider)) ports.set(provider, new Set());
      ports.get(provider).add(port);
      connectBroker();
      hello("provider-connected");
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

chrome.runtime.onStartup.addListener(() => connectBroker());
chrome.runtime.onInstalled.addListener(() => connectBroker());
connectBroker();

const BROKER = "ws://127.0.0.1:3847/bridge";
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

function announceProviders() {
  send({ type: "providers", providers: availableProviders() });
}

function scheduleReconnect() {
  if (reconnectTimer || availableProviders().length === 0) return;
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
  if (availableProviders().length === 0) return;
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;

  try {
    socket = new WebSocket(BROKER);
  } catch (_) {
    scheduleReconnect();
    return;
  }

  socket.onopen = () => {
    reconnectDelay = 500;
    send({ type: "hello", providers: availableProviders(), version: chrome.runtime.getManifest().version });
    stopPing();
    pingTimer = setInterval(() => send({ type: "ping" }), 20000);
  };

  socket.onmessage = (event) => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (message?.type !== "command" || !message.id || !message.provider) return;

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
      port.postMessage(message);
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
      announceProviders();
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
    if (availableProviders().length === 0) {
      if (reconnectTimer) clearTimeout(reconnectTimer);
      reconnectTimer = null;
      stopPing();
      if (socket) {
        try { socket.close(); } catch (_) {}
        socket = null;
      }
    }
  });
});

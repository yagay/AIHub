const BROKER = "http://127.0.0.1:3847";
const ports = new Map();
let polling = false;

function availableProviders() {
  return [...ports.keys()].filter((key) => ports.get(key)?.size > 0);
}

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== "aihub-provider") return;
  let provider = null;

  port.onMessage.addListener((message) => {
    if (message?.type === "hello" && message.provider) {
      provider = message.provider;
      if (!ports.has(provider)) ports.set(provider, new Set());
      ports.get(provider).add(port);
      startPolling();
      return;
    }
    if (message?.type === "result" && message.id) {
      fetch(`${BROKER}/bridge/result`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id: message.id,
          response: message.response || "",
          error: message.error || "",
        }),
      }).catch(() => {});
    }
  });

  port.onDisconnect.addListener(() => {
    if (!provider) return;
    ports.get(provider)?.delete(port);
    if (ports.get(provider)?.size === 0) ports.delete(provider);
  });
});

async function startPolling() {
  if (polling) return;
  polling = true;
  try {
    while (availableProviders().length > 0) {
      const providers = availableProviders();
      try {
        const response = await fetch(`${BROKER}/bridge/poll?providers=${encodeURIComponent(providers.join(","))}`, { cache: "no-store" });
        if (response.status === 200) {
          const command = await response.json();
          const set = ports.get(command.provider);
          const port = set && [...set].at(-1);
          if (port) port.postMessage({ type: "command", ...command });
        }
      } catch (_) {}
      await new Promise((resolve) => setTimeout(resolve, 350));
    }
  } finally {
    polling = false;
  }
}

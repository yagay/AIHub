import { useEffect, useState } from "react";
import { ListItem } from "./ui-lib";

type Provider = {
  id: string;
  name: string;
  authorized: boolean;
  models: Array<{ id: string; name: string }>;
};

declare global {
  interface Window {
    AIHubSystem?: {
      openProvider(providerId: string): void;
    };
  }
}

const GATEWAY = "http://127.0.0.1:3456";
const ALLOWED = new Set([
  "chatgpt-web",
  "claude-web",
  "gemini-web",
  "deepseek-web",
  "grok-web",
]);

export function AIHubGatewaySettings() {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [loading, setLoading] = useState<string | null>(null);
  const [error, setError] = useState("");

  const refresh = async () => {
    try {
      setError("");
      const response = await fetch(`${GATEWAY}/aihub/providers`, { cache: "no-store" });
      if (!response.ok) throw new Error(`Gateway ${response.status}`);
      const data = (await response.json()) as Provider[];
      setProviders(data.filter((provider) => ALLOWED.has(provider.id)));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const authorize = async (provider: Provider) => {
    setLoading(provider.id);
    setError("");
    try {
      window.AIHubSystem?.openProvider(provider.id);
      await new Promise((resolve) => setTimeout(resolve, 700));
      const response = await fetch(
        `${GATEWAY}/aihub/auth/${encodeURIComponent(provider.id)}`,
        { method: "POST" },
      );
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data?.error || `Authorization ${response.status}`);
      await refresh();
      window.location.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(null);
    }
  };

  return (
    <>
      <ListItem
        title="AIHub Web Accounts"
        subTitle={
          error
            ? `Local browser gateway: ${error}`
            : "Uses your logged-in Chrome sessions. No official API key is required."
        }
      >
        <button type="button" onClick={() => void refresh()}>
          Refresh
        </button>
      </ListItem>
      {providers.map((provider) => (
        <ListItem
          key={provider.id}
          title={provider.name}
          subTitle={
            provider.authorized
              ? `Connected · ${provider.models.length} model(s)`
              : "Not connected"
          }
        >
          <button
            type="button"
            disabled={loading !== null}
            onClick={() => void authorize(provider)}
          >
            {loading === provider.id
              ? "Waiting for login…"
              : provider.authorized
                ? "Re-login"
                : "Login"}
          </button>
        </ListItem>
      ))}
    </>
  );
}

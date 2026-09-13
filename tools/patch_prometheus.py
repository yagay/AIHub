#!/usr/bin/env python3
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
marker = "  log('INFO', `Content script loaded on: ${window.location.hostname}`);\n})();"
if marker not in text:
    raise SystemExit("Prometheus content-script footer changed")

bridge = r'''  // AIHub addition: keep the MV3 worker alive through a provider-tab port and
  // delegate commands to Prometheus' existing Browser-Tab implementation.
  const __aihubAdapter = detectAdapter();
  if (__aihubAdapter && ['chatgpt', 'claude', 'gemini', 'deepseek', 'grok'].includes(__aihubAdapter.key)) {
    const __aihubPort = chrome.runtime.connect({ name: 'aihub-provider' });
    __aihubPort.postMessage({ type: 'hello', provider: __aihubAdapter.key });
    __aihubPort.onMessage.addListener((message) => {
      if (message?.type !== 'command' || !message.id) return;
      handleSendQuestion(message.prompt || '', 180000)
        .then((result) => __aihubPort.postMessage({
          type: 'result',
          id: message.id,
          response: result?.response || '',
          error: result?.error || '',
        }))
        .catch((error) => __aihubPort.postMessage({
          type: 'result', id: message.id, response: '', error: String(error?.message || error),
        }));
    });
  }

'''
path.write_text(text.replace(marker, bridge + marker, 1), encoding="utf-8")
print("Patched Prometheus Browser-Tab core for AIHub Titanium IPC")

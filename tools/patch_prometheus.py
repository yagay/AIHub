#!/usr/bin/env python3
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
marker = "  log('INFO', `Content script loaded on: ${window.location.hostname}`);\n})();"
if marker not in text:
    raise SystemExit("Prometheus content-script footer changed")

bridge = r'''  // AIHub addition: keep the MV3 worker alive through a provider-tab port.
  // Use Prometheus v2.3's polling primitives instead of the legacy full-cycle
  // handleSendQuestion(), so background tabs remain responsive and AIHub can see
  // exactly which DOM phase is currently running.
  const __aihubAdapter = detectAdapter();
  if (__aihubAdapter && ['chatgpt', 'claude', 'gemini', 'deepseek', 'grok'].includes(__aihubAdapter.key)) {
    const __aihubPort = chrome.runtime.connect({ name: 'aihub-provider' });
    const __aihubRunning = new Set();

    const __aihubProgress = (id, stage, status = {}) => {
      try {
        __aihubPort.postMessage({
          type: 'progress',
          id,
          stage,
          phase: status?.phase || '',
          textLength: Number(status?.textLength || 0),
          containerCount: Number(status?.containerCount || 0),
        });
      } catch (_) {}
    };

    const __aihubResult = (id, response = '', error = '') => {
      try {
        __aihubPort.postMessage({ type: 'result', id, response, error });
      } catch (_) {}
    };

    const __aihubRun = async (message) => {
      const id = message.id;
      const prompt = message.prompt || '';
      if (__aihubRunning.has(id)) return;
      __aihubRunning.add(id);
      __aihubProgress(id, 'content_received');

      try {
        // Prometheus' send_only helper includes a 30s duplicate-send guard for
        // its own retry machinery. AIHub command ids already provide deduplication,
        // so clear that guard before every genuine user command.
        handleSendOnly._lastSendTime = 0;
        handleSendOnly._lastPrevCount = 0;

        // Prometheus v2.3: type + send and return immediately with response baseline.
        const sent = await handleSendOnly(prompt);
        if (!sent || sent.error) {
          __aihubResult(id, '', sent?.error || `${__aihubAdapter.name}: send failed`);
          return;
        }

        const prevCount = Number(sent.prevCount || 0);
        __aihubProgress(id, 'sent', { phase: 'waiting', containerCount: prevCount });

        const deadline = Date.now() + 180000;
        let lastPhase = '';
        let lastTextLength = -1;
        let stableSince = 0;
        let stableTextLength = -1;

        while (Date.now() < deadline) {
          const status = checkResponseStatus(prevCount) || {};
          if (status.error) {
            __aihubResult(id, '', String(status.error));
            return;
          }

          const phase = status.phase || 'waiting';
          const textLength = Number(status.textLength || 0);
          if (phase !== lastPhase || Math.abs(textLength - lastTextLength) >= 20) {
            __aihubProgress(id, phase, status);
            lastPhase = phase;
            lastTextLength = textLength;
          }

          if (phase === 'stable') {
            // Some providers change DOM structures faster than their "generating"
            // selector can track. Require the measured response length itself to stay
            // unchanged for 2.5s before extracting, otherwise a partial answer could
            // be returned as final.
            if (!stableSince || textLength !== stableTextLength) {
              stableSince = Date.now();
              stableTextLength = textLength;
            }
            if (Date.now() - stableSince >= 2500) {
              const adapter = detectAdapter() || __aihubAdapter;
              const response = extractLastResponse(adapter, prevCount, { userQuestion: prompt });
              if (response) {
                __aihubProgress(id, 'extracted', {
                  phase: 'stable',
                  textLength: response.length,
                  containerCount: status.containerCount || 0,
                });
                __aihubResult(id, response, '');
                return;
              }
              __aihubProgress(id, 'extract_empty', status);
              stableSince = 0;
              stableTextLength = -1;
            }
          } else {
            stableSince = 0;
            stableTextLength = -1;
          }

          // Prometheus sleep() uses MessageChannel for short waits, avoiding
          // Chromium's background-tab setTimeout throttling.
          await sleep(750);
        }

        // One final extraction attempt before returning a timeout.
        const adapter = detectAdapter() || __aihubAdapter;
        const response = extractLastResponse(adapter, prevCount, { userQuestion: prompt });
        if (response) {
          __aihubResult(id, response, '');
        } else {
          __aihubResult(id, '', `${__aihubAdapter.name}: timed out waiting for response`);
        }
      } catch (error) {
        __aihubResult(id, '', String(error?.message || error));
      } finally {
        __aihubRunning.delete(id);
      }
    };

    __aihubPort.postMessage({ type: 'hello', provider: __aihubAdapter.key });
    __aihubPort.onMessage.addListener((message) => {
      if (message?.type !== 'command' || !message.id) return;
      __aihubRun(message);
    });
  }

'''
path.write_text(text.replace(marker, bridge + marker, 1), encoding="utf-8")
print("Patched Prometheus v2.3 polling core for AIHub Titanium IPC")

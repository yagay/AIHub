package com.yagay.aihub.browser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

/** Stateless browser gateway: every request opens a fresh web chat and streams its DOM response. */
public final class ChromeCdpEngine implements BrowserEngine {
    private static final long CHAT_TIMEOUT_MINUTES = 5;

    private static final class Task {
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile CdpConnection connection;
        volatile ChromeTargetManager.Target target;
        volatile CompletableFuture<String> completion;
    }

    private final Context context;
    private final ProviderCatalog providers;
    private final RootCdpBridge rootBridge;
    private final OkHttpClient http;
    private final ChromeTargetManager targets;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public ChromeCdpEngine(Context context) {
        this.context = context.getApplicationContext();
        this.providers = new ProviderCatalog(context);
        this.rootBridge = new RootCdpBridge(context);
        this.http = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        this.targets = new ChromeTargetManager(context, http);
    }

    @Override
    public void chat(String requestId, String modelId, String prompt, ChatListener listener) {
        Task task = new Task();
        Task previous = tasks.putIfAbsent(requestId, task);
        if (previous != null) {
            listener.onError("duplicate_request", "Duplicate request id");
            return;
        }
        executor.execute(() -> runChat(requestId, modelId, prompt, listener, task));
    }

    private void runChat(String requestId, String modelId, String prompt, ChatListener listener, Task task) {
        BrowserProvider provider = providers.requireModel(modelId);
        try {
            rootBridge.ensureStarted();
            if (task.cancelled.get()) throw new CancellationException();

            ChromeTargetManager.Target target = targets.openTarget(provider);
            task.target = target;
            if (target.webSocketUrl() == null || target.webSocketUrl().isBlank()) {
                throw new BrowserStateException("target_unavailable", "Chrome target has no DevTools endpoint");
            }

            CdpConnection cdp = new CdpConnection(http, target.webSocketUrl());
            task.connection = cdp;
            cdp.connect(10_000);
            cdp.command("Page.enable", null, 5_000);
            cdp.command("Runtime.enable", null, 5_000);

            JSONObject navigate = new JSONObject().put("url", provider.homeUrl());
            cdp.command("Page.navigate", navigate, 10_000);
            waitForComposer(cdp, provider, 60_000);
            if (task.cancelled.get()) throw new CancellationException();

            CompletableFuture<String> completion = new CompletableFuture<>();
            task.completion = completion;
            AtomicReference<String> latest = new AtomicReference<>("");

            cdp.setEventHandler((method, params) -> {
                if (!"Runtime.bindingCalled".equals(method)) return;
                if (!"aihubEmit".equals(params.optString("name"))) return;
                try {
                    JSONObject event = new JSONObject(params.optString("payload", "{}"));
                    if (!requestId.equals(event.optString("requestId"))) return;
                    String kind = event.optString("kind");
                    String text = event.optString("text", latest.get());
                    if ("update".equals(kind)) {
                        latest.set(text);
                        listener.onUpdate(text, event.optString("chunk", ""));
                    } else if ("done".equals(kind)) {
                        if (!text.isBlank()) latest.set(text);
                        completion.complete(latest.get());
                    } else if ("error".equals(kind)) {
                        completion.completeExceptionally(new IllegalStateException(event.optString("message", "Page automation failed")));
                    }
                } catch (Exception ignored) {}
            });

            cdp.command("Runtime.addBinding", new JSONObject().put("name", "aihubEmit"), 5_000);
            JSONObject evaluate = new JSONObject()
                    .put("expression", buildChatScript(provider, requestId, prompt))
                    .put("userGesture", true)
                    .put("awaitPromise", false);
            cdp.command("Runtime.evaluate", evaluate, 10_000);

            String result = completion.get(CHAT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (task.cancelled.get()) throw new CancellationException();
            if (result == null || result.isBlank()) {
                throw new IllegalStateException("The website returned an empty response");
            }
            listener.onFinish(result);
        } catch (CancellationException cancelled) {
            // The UI already handles AbortController cancellation.
        } catch (Exception error) {
            String code = "browser_error";
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            Throwable cursor = error;
            while (cursor != null) {
                if (cursor instanceof BrowserStateException state) {
                    code = state.code();
                    message = state.getMessage();
                    break;
                }
                cursor = cursor.getCause();
            }
            listener.onError(code, message);
        } finally {
            if (task.connection != null) task.connection.close();
            if (task.target != null) targets.closeTarget(task.target);
            tasks.remove(requestId);
        }
    }

    private static void waitForComposer(CdpConnection cdp, BrowserProvider provider, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String expression = "(() => { const s=" + new JSONArray(provider.inputSelectors())
                + "; return s.some(x => { try { return !!document.querySelector(x); } catch(e) { return false; } }); })()";
        while (System.currentTimeMillis() < deadline) {
            JSONObject result = cdp.command(
                    "Runtime.evaluate",
                    new JSONObject().put("expression", expression).put("returnByValue", true),
                    5_000);
            JSONObject value = result.optJSONObject("result");
            if (value != null && value.optBoolean("value", false)) return;
            Thread.sleep(350);
        }
        throw new BrowserStateException(
                "login_required",
                provider.name() + " composer was not found. AIHub will open the site in Chrome; sign in there, return, and retry.");
    }

    private static String buildChatScript(BrowserProvider provider, String requestId, String prompt) {
        String template = """
                (() => {
                  const cfg = __CFG__;
                  const requestId = __RID__;
                  const prompt = __PROMPT__;
                  const emit = (kind, data = {}) => {
                    try { globalThis.aihubEmit(JSON.stringify({ requestId, kind, ...data })); } catch (_) {}
                  };
                  const visible = (el) => !!el && !el.disabled && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
                  const first = (selectors, requireVisible = false) => {
                    for (const selector of selectors || []) {
                      try {
                        const el = document.querySelector(selector);
                        if (el && !el.disabled && (!requireVisible || visible(el))) return el;
                      } catch (_) {}
                    }
                    return null;
                  };
                  const all = (selectors) => {
                    const out = [];
                    for (const selector of selectors || []) {
                      try { out.push(...document.querySelectorAll(selector)); } catch (_) {}
                    }
                    return [...new Set(out)];
                  };
                  const semantic = (words) => {
                    const wanted = words.map(x => x.toLowerCase());
                    let best = null, score = 0;
                    for (const el of document.querySelectorAll('button,[role="button"]')) {
                      if (el.disabled) continue;
                      const text = [el.getAttribute('aria-label'), el.getAttribute('title'), el.textContent]
                        .filter(Boolean).join(' ').toLowerCase();
                      let s = visible(el) ? 1 : 0;
                      for (const word of wanted) {
                        if (text === word) s += 10;
                        else if (text.includes(word)) s += 5;
                      }
                      if (s > score) { score = s; best = el; }
                    }
                    return score > 1 ? best : null;
                  };
                  const input = first(cfg.input, true) || first(cfg.input, false);
                  if (!input) { emit('error', { message: 'Composer not found' }); return; }
                  input.focus();
                  if ('value' in input) {
                    let proto = input, setter = null;
                    while (proto && !setter) {
                      const d = Object.getOwnPropertyDescriptor(proto, 'value');
                      if (d && d.set) setter = d.set;
                      proto = Object.getPrototypeOf(proto);
                    }
                    if (setter) setter.call(input, prompt); else input.value = prompt;
                  } else {
                    try {
                      const selection = getSelection();
                      const range = document.createRange();
                      range.selectNodeContents(input);
                      selection.removeAllRanges();
                      selection.addRange(range);
                      if (!document.execCommand('insertText', false, prompt)) input.textContent = prompt;
                    } catch (_) { input.textContent = prompt; }
                  }
                  try {
                    input.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: prompt }));
                  } catch (_) { input.dispatchEvent(new Event('input', { bubbles: true, composed: true })); }
                  input.dispatchEvent(new Event('change', { bubbles: true, composed: true }));

                  let send = first(cfg.send, true) || first(cfg.send, false) || semantic(['send', 'submit', '发送', '提交', 'ask']);
                  if (send) send.click();
                  else {
                    input.dispatchEvent(new KeyboardEvent('keydown', { key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true }));
                    input.dispatchEvent(new KeyboardEvent('keyup', { key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true }));
                  }

                  const state = { cancelled: false, timer: null };
                  globalThis.__aihubTask = state;
                  let last = '';
                  let lastChange = Date.now();
                  const startedAt = Date.now();
                  const read = () => {
                    let nodes = all(cfg.assistant).filter(visible);
                    if (!nodes.length) {
                      nodes = [...document.querySelectorAll("main [data-message-author-role='assistant'], main [data-testid*='assistant'], main .model-response-text, main .markdown, main .prose")].filter(visible);
                    }
                    for (let i = nodes.length - 1; i >= 0; i--) {
                      const text = (nodes[i].innerText || nodes[i].textContent || '').trim();
                      if (text) return text;
                    }
                    return '';
                  };
                  state.timer = setInterval(() => {
                    if (state.cancelled) {
                      clearInterval(state.timer);
                      emit('error', { message: 'aborted' });
                      return;
                    }
                    const text = read();
                    if (text && text !== last) {
                      const chunk = text.startsWith(last) ? text.slice(last.length) : text;
                      last = text;
                      lastChange = Date.now();
                      emit('update', { text, chunk });
                    }
                    const stop = first(cfg.stop, true);
                    if (last && !stop && Date.now() - lastChange > 2800) {
                      clearInterval(state.timer);
                      emit('done', { text: last });
                      return;
                    }
                    if (Date.now() - startedAt > 290000) {
                      clearInterval(state.timer);
                      if (last) emit('done', { text: last });
                      else emit('error', { message: 'Timed out waiting for the website response' });
                    }
                  }, 180);
                })();
                """;
        return template
                .replace("__CFG__", provider.scriptConfig().toString())
                .replace("__RID__", JSONObject.quote(requestId))
                .replace("__PROMPT__", JSONObject.quote(prompt));
    }

    @Override
    public void cancel(String requestId) {
        Task task = tasks.get(requestId);
        if (task == null) return;
        task.cancelled.set(true);
        CompletableFuture<String> completion = task.completion;
        if (completion != null) completion.completeExceptionally(new CancellationException());
        CdpConnection cdp = task.connection;
        if (cdp != null) {
            executor.execute(() -> {
                try {
                    String expression = "(() => { if (globalThis.__aihubTask) globalThis.__aihubTask.cancelled=true;"
                            + "const b=[...document.querySelectorAll('button,[role=button]')].find(x => /stop|停止/i.test((x.getAttribute('aria-label')||'')+' '+(x.textContent||''))); if(b)b.click(); })()";
                    cdp.command("Runtime.evaluate", new JSONObject().put("expression", expression), 3_000);
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    public void openProvider(String modelId) {
        BrowserProvider provider = providers.requireModel(modelId);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(provider.homeUrl()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public void shutdown() {
        for (String requestId : tasks.keySet()) cancel(requestId);
        executor.shutdownNow();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}

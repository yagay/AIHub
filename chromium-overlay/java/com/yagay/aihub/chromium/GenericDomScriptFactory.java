package com.yagay.aihub.chromium;

import com.yagay.aihub.core.ProviderConfig;
import java.util.List;

/**
 * Builds generic DOM actions. Provider selectors are only fallbacks; semantic discovery runs first.
 * This class deliberately contains no provider-specific branching.
 */
public final class GenericDomScriptFactory {
    private GenericDomScriptFactory() {}

    public static String fillAndSend(String text, ProviderConfig provider) {
        String inputFallbacks = jsArray(provider.inputSelectors());
        String sendFallbacks = jsArray(provider.sendSelectors());
        return """
            (() => {
              const payload = %s;
              const inputFallbacks = %s;
              const sendFallbacks = %s;
              const visible = el => !!el && !el.disabled && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
              const score = el => {
                if (!visible(el)) return -1;
                const r = el.getBoundingClientRect();
                let s = Math.max(0, r.width * r.height);
                const tag = (el.tagName || '').toLowerCase();
                if (tag === 'textarea') s += 1000000;
                if (el.getAttribute('role') === 'textbox') s += 900000;
                if (el.isContentEditable) s += 800000;
                if (r.top > innerHeight * 0.45) s += 300000;
                return s;
              };
              const firstSelector = selectors => {
                for (const selector of selectors) {
                  try {
                    const el = document.querySelector(selector);
                    if (visible(el)) return el;
                  } catch (_) {}
                }
                return null;
              };
              let inputs = [...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')]
                .filter(visible).sort((a,b) => score(b)-score(a));
              let input = inputs[0] || firstSelector(inputFallbacks);
              if (!input) return JSON.stringify({ok:false,stage:'input'});
              input.focus();
              if ('value' in input) {
                const proto = Object.getPrototypeOf(input);
                const d = Object.getOwnPropertyDescriptor(proto, 'value');
                if (d && d.set) d.set.call(input, payload); else input.value = payload;
              } else {
                input.textContent = payload;
              }
              input.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:payload}));
              input.dispatchEvent(new Event('change', {bubbles:true}));

              const words = ['send','submit','发送','提交','ask','go'];
              const candidates = [...document.querySelectorAll('button,[role="button"]')]
                .filter(visible)
                .filter(el => {
                  const t = [el.getAttribute('aria-label'), el.getAttribute('title'), el.textContent]
                    .filter(Boolean).join(' ').toLowerCase();
                  return words.some(w => t.includes(w));
                });
              let send = candidates[0] || firstSelector(sendFallbacks);
              if (send) {
                send.click();
                return JSON.stringify({ok:true,method:'button'});
              }
              input.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
              input.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
              return JSON.stringify({ok:true,method:'enter'});
            })()
            """.formatted(jsString(text), inputFallbacks, sendFallbacks);
    }

    public static String newChat(ProviderConfig provider) {
        return clickAction(provider.newChatSelectors(), List.of("new chat", "new conversation", "新对话", "新聊天"), "new_chat");
    }

    public static String stop(ProviderConfig provider) {
        return clickAction(provider.stopSelectors(), List.of("stop", "停止", "停止生成"), "stop");
    }

    /** Finds the site's own attachment/file control and activates it. */
    public static String findAttachmentControl() {
        return """
            (() => {
              const visible = el => !!el && !el.disabled && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
              const direct = [...document.querySelectorAll('input[type="file"]')].find(el => !el.disabled);
              if (direct) { direct.click(); return JSON.stringify({ok:true,method:'file-input'}); }
              const words = ['attach','upload','add file','file','附件','上传','添加文件'];
              for (const el of document.querySelectorAll('button,[role="button"],a')) {
                if (!visible(el)) continue;
                const t = [el.getAttribute('aria-label'), el.getAttribute('title'), el.textContent]
                  .filter(Boolean).join(' ').toLowerCase();
                if (words.some(w => t.includes(w))) { el.click(); return JSON.stringify({ok:true,method:'semantic'}); }
              }
              return JSON.stringify({ok:false,action:'attach'});
            })()
            """;
    }

    /** Returns a small JSON health report used by diagnostics after providers change their DOM. */
    public static String probe(ProviderConfig provider) {
        return """
            (() => {
              const inputFallbacks = %s;
              const sendFallbacks = %s;
              const newChatFallbacks = %s;
              const stopFallbacks = %s;
              const visible = el => !!el && !el.disabled && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
              const any = selectors => selectors.some(s => { try { return visible(document.querySelector(s)); } catch (_) { return false; } });
              const semantic = words => [...document.querySelectorAll('button,[role="button"],a')].some(el => {
                if (!visible(el)) return false;
                const t = [el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();
                return words.some(w => t.includes(w));
              });
              const inputs = [...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(visible);
              const fileInputs = [...document.querySelectorAll('input[type="file"]')].filter(el => !el.disabled);
              return JSON.stringify({
                ok: inputs.length > 0 || any(inputFallbacks),
                url: location.href,
                title: document.title,
                input: inputs.length > 0 || any(inputFallbacks),
                send: semantic(['send','submit','发送','提交','ask','go']) || any(sendFallbacks),
                newChat: semantic(['new chat','new conversation','新对话','新聊天']) || any(newChatFallbacks),
                stop: semantic(['stop','停止','停止生成']) || any(stopFallbacks),
                file: fileInputs.length > 0
              });
            })()
            """.formatted(
                    jsArray(provider.inputSelectors()),
                    jsArray(provider.sendSelectors()),
                    jsArray(provider.newChatSelectors()),
                    jsArray(provider.stopSelectors()));
    }

    private static String clickAction(List<String> fallbackSelectors, List<String> words, String action) {
        return """
            (() => {
              const fallbacks = %s;
              const words = %s;
              const visible = el => !!el && !el.disabled && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
              for (const el of document.querySelectorAll('button,[role="button"],a')) {
                if (!visible(el)) continue;
                const t = [el.getAttribute('aria-label'), el.getAttribute('title'), el.textContent]
                  .filter(Boolean).join(' ').toLowerCase();
                if (words.some(w => t.includes(w))) { el.click(); return JSON.stringify({ok:true,method:'semantic',action:%s}); }
              }
              for (const selector of fallbacks) {
                try { const el = document.querySelector(selector); if (visible(el)) { el.click(); return JSON.stringify({ok:true,method:'selector',action:%s}); } } catch (_) {}
              }
              return JSON.stringify({ok:false,action:%s});
            })()
            """.formatted(jsArray(fallbackSelectors), jsArray(words), jsString(action), jsString(action), jsString(action));
    }

    private static String jsArray(List<String> values) {
        return "[" + values.stream().map(GenericDomScriptFactory::jsString).reduce((a,b) -> a + "," + b).orElse("") + "]";
    }

    private static String jsString(String value) {
        if (value == null) return "null";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029") + "\"";
    }
}

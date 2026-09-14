(() => {
  const cfg = window.__AIHUB_CONFIG__ || {};

  const all = (selectors) => {
    const out = [];
    (selectors || []).forEach((selector) => {
      try { document.querySelectorAll(selector).forEach((node) => out.push(node)); } catch (_) {}
    });
    return out;
  };

  const isVisible = (node) => {
    if (!node) return false;
    const rect = node.getBoundingClientRect();
    const style = getComputedStyle(node);
    return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden";
  };

  const firstVisible = (selectors) => all(selectors).find(isVisible) || null;
  const firstUsable = (selectors) => all(selectors).find((node) => {
    if (!isVisible(node)) return false;
    if (node.disabled) return false;
    if (node.getAttribute?.("aria-disabled") === "true") return false;
    return true;
  }) || null;

  const setEditorText = (element, text) => {
    element.focus();
    try { element.click(); } catch (_) {}

    if (element instanceof HTMLTextAreaElement || element instanceof HTMLInputElement) {
      const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
      if (descriptor && descriptor.set) descriptor.set.call(element, text); else element.value = text;
      element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: text, composed: true }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    }

    if (element.isContentEditable) {
      const selection = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(element);
      selection.removeAllRanges();
      selection.addRange(range);

      // Modern ProseMirror/Lexical editors often react to beforeinput rather
      // than a direct textContent assignment.
      try {
        element.dispatchEvent(new InputEvent("beforeinput", {
          inputType: "deleteContentBackward", bubbles: true, cancelable: true, composed: true
        }));
        element.dispatchEvent(new InputEvent("beforeinput", {
          inputType: "insertText", data: text, bubbles: true, cancelable: true, composed: true
        }));
      } catch (_) {}

      let inserted = false;
      try {
        document.execCommand("delete", false);
        inserted = document.execCommand("insertText", false, text);
      } catch (_) {}
      if (!inserted && !(element.innerText || element.textContent || "").trim()) element.textContent = text;
      element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: text, composed: true }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    }
    return false;
  };

  const pressEnter = (element) => {
    try { element.focus(); } catch (_) {}
    const opts = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
    element.dispatchEvent(new KeyboardEvent("keydown", opts));
    element.dispatchEvent(new KeyboardEvent("keypress", opts));
    element.dispatchEvent(new KeyboardEvent("keyup", opts));
  };

  const textOf = (node) => node ? (node.innerText || node.textContent || "").replace(/\u00a0/g, " ").trim() : "";

  const nodeSummary = (node) => {
    if (!node) return null;
    const rect = node.getBoundingClientRect();
    return {
      tag: node.tagName || "",
      id: node.id || "",
      role: node.getAttribute?.("role") || "",
      testid: node.getAttribute?.("data-testid") || "",
      disabled: !!node.disabled || node.getAttribute?.("aria-disabled") === "true",
      width: Math.round(rect.width),
      height: Math.round(rect.height)
    };
  };

  const responseNodes = () => all(cfg.responseSelectors);

  window.__AIHUB__ = {
    isLoggedIn() {
      return !!firstVisible(cfg.inputSelectors) || !!firstVisible(cfg.loggedInSelectors);
    },

    send(text) {
      const editor = firstVisible(cfg.inputSelectors);
      if (!editor) return "no-input";
      if (!setEditorText(editor, text)) return "input-failed";

      const immediate = firstUsable(cfg.sendSelectors);
      if (immediate) {
        immediate.click();
        return "ok";
      }

      // React/Angular sites often render/enable the send button only after
      // their state sees the input event. Retry several times before Enter.
      [120, 280, 520, 900].forEach((delay, index, delays) => {
        setTimeout(() => {
          const delayed = firstUsable(cfg.sendSelectors);
          if (delayed) delayed.click();
          else if (index === delays.length - 1) pressEnter(editor);
        }, delay);
      });
      return "ok";
    },

    extractLastResponse() {
      const nodes = responseNodes();
      for (let i = nodes.length - 1; i >= 0; i--) {
        const text = textOf(nodes[i]);
        if (text) return text;
      }
      return "";
    },

    isGenerating() { return !!firstVisible(cfg.stopSelectors); },

    stop() {
      const stop = firstUsable(cfg.stopSelectors);
      if (!stop) return "not-generating";
      stop.click();
      return "ok";
    },

    newChat() {
      const button = firstUsable(cfg.newChatSelectors) || firstVisible(cfg.newChatSelectors);
      if (button) { button.click(); return "ok"; }
      if (cfg.homeUrl) { location.href = cfg.homeUrl; return "navigating"; }
      return "not-found";
    },

    probeSummary() {
      const responses = responseNodes();
      let lastResponseChars = 0;
      for (let i = responses.length - 1; i >= 0; i--) {
        const text = textOf(responses[i]);
        if (text) { lastResponseChars = text.length; break; }
      }
      return {
        viewport: { width: innerWidth, height: innerHeight, dpr: devicePixelRatio },
        inputCount: all(cfg.inputSelectors).length,
        visibleInput: nodeSummary(firstVisible(cfg.inputSelectors)),
        loggedInMarkerCount: all(cfg.loggedInSelectors).length,
        sendCount: all(cfg.sendSelectors).length,
        visibleSend: nodeSummary(firstVisible(cfg.sendSelectors)),
        responseCount: responses.length,
        lastResponseChars,
        stopCount: all(cfg.stopSelectors).length,
        path: location.pathname,
        bodyChars: (document.body?.innerText || "").length
      };
    }
  };
})();

(() => {
  const cfg = window.__AIHUB_CONFIG__ || {};

  const all = (selectors) => {
    const seen = new Set();
    const out = [];
    (selectors || []).forEach((selector) => {
      try {
        document.querySelectorAll(selector).forEach((node) => {
          if (!seen.has(node)) {
            seen.add(node);
            out.push(node);
          }
        });
      } catch (_) {}
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
      author: node.getAttribute?.("data-message-author-role") || node.getAttribute?.("data-turn") || "",
      width: Math.round(rect.width),
      height: Math.round(rect.height)
    };
  };

  const responseNodes = () => all(cfg.responseSelectors);

  const textFromTurn = (turn) => {
    if (!turn) return "";
    const contentSelectors = cfg.responseContentSelectors || [];
    for (const selector of contentSelectors) {
      try {
        const nodes = turn.querySelectorAll(selector);
        for (let i = nodes.length - 1; i >= 0; i--) {
          const value = textOf(nodes[i]);
          if (value) return value;
        }
      } catch (_) {}
    }

    const clone = turn.cloneNode(true);
    try {
      clone.querySelectorAll("button, nav, [role='toolbar'], [data-testid*='action'], [class*='action']").forEach((node) => node.remove());
    } catch (_) {}
    return textOf(clone);
  };

  const responseViaCopyButton = () => {
    const buttons = all(cfg.copyButtonSelectors);
    for (let i = buttons.length - 1; i >= 0; i--) {
      const button = buttons[i];
      let turn = null;
      for (const selector of (cfg.turnSelectors || [])) {
        try {
          turn = button.closest(selector);
          if (turn) break;
        } catch (_) {}
      }
      if (!turn) turn = button.closest("article, section, [data-testid*='conversation-turn']");
      const value = textFromTurn(turn);
      if (value) return value;
    }
    return "";
  };

  const extractResponse = () => {
    const nodes = responseNodes();
    for (let i = nodes.length - 1; i >= 0; i--) {
      const text = textOf(nodes[i]);
      if (text) return text;
    }
    return responseViaCopyButton();
  };

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

      let submitted = false;
      const delays = [120, 280, 520, 900];
      delays.forEach((delay, index) => {
        setTimeout(() => {
          if (submitted) return;
          const delayed = firstUsable(cfg.sendSelectors);
          if (delayed) {
            submitted = true;
            delayed.click();
          } else if (index === delays.length - 1) {
            submitted = true;
            pressEnter(editor);
          }
        }, delay);
      });
      return "ok";
    },

    extractLastResponse() {
      return extractResponse();
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
      const extracted = extractResponse();
      const turns = all(cfg.turnSelectors);
      const assistants = all(cfg.assistantMarkerSelectors);
      const copyButtons = all(cfg.copyButtonSelectors);
      return {
        viewport: { width: innerWidth, height: innerHeight, dpr: devicePixelRatio },
        inputCount: all(cfg.inputSelectors).length,
        visibleInput: nodeSummary(firstVisible(cfg.inputSelectors)),
        loggedInMarkerCount: all(cfg.loggedInSelectors).length,
        sendCount: all(cfg.sendSelectors).length,
        visibleSend: nodeSummary(firstVisible(cfg.sendSelectors)),
        responseCount: responses.length,
        extractedChars: extracted.length,
        turnCount: turns.length,
        assistantMarkerCount: assistants.length,
        copyButtonCount: copyButtons.length,
        lastTurn: nodeSummary(turns.length ? turns[turns.length - 1] : null),
        stopCount: all(cfg.stopSelectors).length,
        path: location.pathname,
        bodyChars: (document.body?.innerText || "").length
      };
    }
  };
})();

(() => {
  const cfg = window.__AIHUB_CONFIG__ || {};
  const all = (selectors) => {
    const out = [];
    (selectors || []).forEach((selector) => {
      try { document.querySelectorAll(selector).forEach((node) => out.push(node)); } catch (_) {}
    });
    return out;
  };
  const firstVisible = (selectors) => all(selectors).find((node) => {
    const rect = node.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }) || null;
  const setEditorText = (element, text) => {
    element.focus();
    if (element instanceof HTMLTextAreaElement || element instanceof HTMLInputElement) {
      const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
      if (descriptor && descriptor.set) descriptor.set.call(element, text); else element.value = text;
      element.dispatchEvent(new Event("input", { bubbles: true }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    }
    if (element.isContentEditable) {
      const selection = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(element);
      selection.removeAllRanges();
      selection.addRange(range);
      let inserted = false;
      try { inserted = document.execCommand("insertText", false, text); } catch (_) {}
      if (!inserted) element.textContent = text;
      element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: text }));
      return true;
    }
    return false;
  };
  const textOf = (node) => node ? (node.innerText || node.textContent || "").trim() : "";
  window.__AIHUB__ = {
    isLoggedIn() { return !!firstVisible(cfg.inputSelectors); },
    send(text) {
      const editor = firstVisible(cfg.inputSelectors);
      if (!editor) return "no-input";
      if (!setEditorText(editor, text)) return "input-failed";
      const send = firstVisible(cfg.sendSelectors);
      if (!send) return "no-send";
      send.click();
      return "ok";
    },
    extractLastResponse() {
      const nodes = all(cfg.responseSelectors);
      for (let i = nodes.length - 1; i >= 0; i--) {
        const text = textOf(nodes[i]);
        if (text) return text;
      }
      return "";
    },
    isGenerating() { return !!firstVisible(cfg.stopSelectors); },
    stop() {
      const stop = firstVisible(cfg.stopSelectors);
      if (!stop) return "not-generating";
      stop.click();
      return "ok";
    },
    newChat() {
      const button = firstVisible(cfg.newChatSelectors);
      if (button) { button.click(); return "ok"; }
      if (cfg.homeUrl) { location.href = cfg.homeUrl; return "navigating"; }
      return "not-found";
    }
  };
})();

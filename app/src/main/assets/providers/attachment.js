(() => {
  const api = window.__AIHUB__;
  if (!api) return;
  const cfg = window.__AIHUB_CONFIG__ || {};
  const state = window.__AIHUB_ATTACHMENT_STATE__ || (window.__AIHUB_ATTACHMENT_STATE__ = {});

  const simpleHash = (value) => {
    let hash = 2166136261;
    const source = String(value || "");
    for (let i = 0; i < source.length; i++) {
      hash ^= source.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };

  const visible = (node) => {
    if (!node) return false;
    const rect = node.getBoundingClientRect();
    const style = getComputedStyle(node);
    return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden";
  };

  const fileInputs = () => Array.from(document.querySelectorAll("input[type='file']"))
    .filter((node) => !node.disabled && node.getAttribute("aria-disabled") !== "true");

  const scoreFileInput = (input) => {
    let score = 0;
    const accept = (input.accept || "").toLowerCase();
    if (input.multiple) score += 8;
    if (accept.includes("image")) score += 6;
    if (accept.includes("pdf") || accept.includes("text") || accept.includes("document")) score += 4;
    if (input.closest("form")) score += 3;
    if (input.closest("[class*='composer'], [class*='prompt'], [class*='input'], [data-testid*='composer']")) score += 8;
    if (visible(input)) score += 2;
    return score;
  };

  const bestFileInput = () => fileInputs().sort((a, b) => scoreFileInput(b) - scoreFileInput(a))[0] || null;

  const genericAttachmentButtonSelectors = [
    "[data-testid='composer-plus-btn']", "[data-testid*='attach' i]", "[data-testid*='upload' i]",
    "button[aria-label*='attach' i]", "button[aria-label*='upload' i]", "button[aria-label*='add file' i]",
    "button[aria-label*='add photo' i]", "button[aria-label*='photo' i]", "button[title*='attach' i]",
    "button[title*='upload' i]", "[role='button'][aria-label*='attach' i]", "[role='button'][aria-label*='upload' i]"
  ];
  const attachmentButtonSelectors = [...(cfg.attachmentButtonSelectors || []), ...genericAttachmentButtonSelectors];

  const findAttachmentButton = () => {
    for (const selector of attachmentButtonSelectors) {
      try {
        const nodes = Array.from(document.querySelectorAll(selector));
        const node = nodes.find((it) => visible(it) && !it.disabled && it.getAttribute?.("aria-disabled") !== "true");
        if (node) return node;
      } catch (_) {}
    }
    return null;
  };

  const findUploadMenuItem = () => {
    for (const selector of (cfg.uploadMenuItemSelectors || [])) {
      try {
        const nodes = Array.from(document.querySelectorAll(selector));
        for (const node of nodes) {
          const clickable = node.closest?.("button, [role='menuitem'], [role='option'], [role='button'], .mat-mdc-menu-item") || node;
          if (visible(clickable)) return clickable;
        }
      } catch (_) {}
    }
    const candidates = Array.from(document.querySelectorAll("button, [role='menuitem'], [role='option'], [role='button'], .mat-mdc-menu-item"));
    const rx = /(upload files?|attach files?|add files?|photos?\s*&\s*files?|上传文件|上传|附件|添加文件)/i;
    return candidates.find((node) => visible(node) && rx.test((node.innerText || node.textContent || node.getAttribute?.("aria-label") || "").trim())) || null;
  };

  const rememberSelection = (input) => {
    const count = Number(input?.files?.length || 0);
    if (count <= 0) return;
    state.lastAttachedCount = count;
    state.lastAttachedAt = Date.now();
    state.lastInputAccept = input.accept || "";
    state.lastInputMultiple = !!input.multiple;
    state.lastStrategy = "webview-file-chooser";
  };

  if (!state.changeListenerInstalled) {
    state.changeListenerInstalled = true;
    document.addEventListener("change", (event) => {
      const input = event.target;
      if (input instanceof HTMLInputElement && input.type === "file") rememberSelection(input);
    }, true);
  }

  api.prepareAttachmentInput = () => {
    if (bestFileInput()) return "ready";
    const menuItem = findUploadMenuItem();
    if (menuItem) {
      menuItem.click();
      state.lastPrepare = "clicked-upload-item";
      return "clicked-upload-item";
    }
    const button = findAttachmentButton();
    if (!button) return "not-found";
    button.click();
    state.lastPrepare = "opened-menu";
    return "opened-menu";
  };

  api.openAttachmentPicker = () => {
    const direct = bestFileInput();
    if (direct) {
      direct.click();
      state.lastPicker = "direct-input";
      return "opened-input";
    }

    const menuItem = findUploadMenuItem();
    if (menuItem) {
      menuItem.click();
      state.lastPicker = "menu-item";
      return "opened-button";
    }

    const button = findAttachmentButton();
    if (!button) return "not-found";
    button.click();
    state.lastPicker = "attachment-button";

    let completed = false;
    [120, 280, 520, 900].forEach((delay) => {
      setTimeout(() => {
        if (completed) return;
        const input = bestFileInput();
        if (input) {
          completed = true;
          input.click();
          state.lastPicker = `delayed-input-${delay}`;
          return;
        }
        const item = findUploadMenuItem();
        if (item) {
          completed = true;
          item.click();
          state.lastPicker = `delayed-menu-${delay}`;
        }
      }, delay);
    });
    return "scheduled";
  };

  api.attachStagedFiles = () => "standard-picker-only";

  api.attachmentProbe = () => {
    const inputs = fileInputs();
    const input = bestFileInput();
    const liveCount = inputs.reduce((sum, it) => sum + Number(it.files?.length || 0), 0);
    return {
      inputCount: inputs.length,
      accepts: inputs.slice(0, 6).map((it) => it.accept || "*/*"),
      multiples: inputs.slice(0, 6).map((it) => !!it.multiple),
      bestInputAccept: input?.accept || "",
      bestInputMultiple: !!input?.multiple,
      visibleAttachmentButton: !!findAttachmentButton(),
      visibleUploadMenuItem: !!findUploadMenuItem(),
      liveFilesCount: liveCount,
      lastAttachedCount: Number(state.lastAttachedCount || 0),
      lastAttachedAgeMs: state.lastAttachedAt ? Math.max(0, Date.now() - state.lastAttachedAt) : -1,
      lastInputAccept: state.lastInputAccept || "",
      lastInputMultiple: !!state.lastInputMultiple,
      lastStrategy: state.lastStrategy || "",
      lastPrepare: state.lastPrepare || "",
      lastPicker: state.lastPicker || "",
      pathHash: simpleHash(location.pathname)
    };
  };
})();

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

  const b64ToBytes = (value) => {
    const raw = atob(value || "");
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  };

  const stagedFiles = () => {
    const bridge = window.AIHubNativeFiles;
    if (!bridge || typeof bridge.count !== "function") return [];
    const total = Number(bridge.count()) || 0;
    const result = [];
    for (let i = 0; i < total; i++) {
      const parts = [];
      const chunks = Number(bridge.chunkCount(i)) || 0;
      for (let part = 0; part < chunks; part++) {
        const encoded = bridge.chunk(i, part);
        if (encoded) parts.push(b64ToBytes(encoded));
      }
      const name = String(bridge.name(i) || `attachment-${i + 1}`);
      const mime = String(bridge.mime(i) || "application/octet-stream");
      result.push(new File(parts, name, { type: mime, lastModified: Date.now() }));
    }
    return result;
  };

  const dispatchFileEvents = (input) => {
    input.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
    input.dispatchEvent(new Event("change", { bubbles: true, composed: true }));
  };

  const assignWithDataTransfer = (input, files) => {
    let data = null;
    try { data = new DataTransfer(); } catch (_) {}
    if (!data) {
      try { data = new ClipboardEvent("").clipboardData; } catch (_) {}
    }
    if (!data?.items) return 0;
    const max = input.multiple ? files.length : Math.min(files.length, 1);
    for (let i = 0; i < max; i++) {
      try { data.items.add(files[i]); } catch (_) {}
    }
    if (!data.files || data.files.length === 0) return 0;
    try {
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "files");
      if (descriptor?.set) descriptor.set.call(input, data.files); else input.files = data.files;
      dispatchFileEvents(input);
      state.lastStrategy = "native-filelist";
      return data.files.length;
    } catch (_) { return 0; }
  };

  const assignWithInstanceOverride = (input, files) => {
    const max = input.multiple ? files.length : Math.min(files.length, 1);
    const selected = files.slice(0, max);
    if (!selected.length) return 0;
    const fileListLike = selected.slice();
    fileListLike.item = (index) => fileListLike[index] || null;
    try {
      Object.defineProperty(input, "files", { configurable: true, enumerable: true, get: () => fileListLike });
      dispatchFileEvents(input);
      state.lastStrategy = "instance-files-override";
      return selected.length;
    } catch (_) { return 0; }
  };

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
    [100, 240, 480].forEach((delay) => {
      setTimeout(() => {
        if (bestFileInput()) return;
        const item = findUploadMenuItem();
        if (item) {
          try { item.click(); state.lastPrepare = "clicked-upload-item"; } catch (_) {}
        }
      }, delay);
    });
    return "opened-menu";
  };

  api.attachStagedFiles = () => {
    const bridge = window.AIHubNativeFiles;
    if (!bridge || typeof bridge.count !== "function") return "no-bridge";
    const input = bestFileInput();
    if (!input) return "no-input";
    const files = stagedFiles();
    if (!files.length) return "no-files";
    let attached = assignWithDataTransfer(input, files);
    if (attached <= 0) attached = assignWithInstanceOverride(input, files);
    if (attached > 0) {
      state.lastAttachedCount = attached;
      state.lastAttachedAt = Date.now();
      state.lastInputAccept = input.accept || "";
      state.lastInputMultiple = !!input.multiple;
    }
    return `attached:${attached}`;
  };

  api.openAttachmentPicker = () => {
    const input = bestFileInput();
    if (input) { input.click(); return "opened-input"; }
    const button = findAttachmentButton();
    if (!button) return "not-found";
    button.click();
    return "opened-button";
  };

  api.attachmentProbe = () => {
    const inputs = fileInputs();
    const input = bestFileInput();
    return {
      inputCount: inputs.length,
      accepts: inputs.slice(0, 6).map((it) => it.accept || "*/*"),
      multiples: inputs.slice(0, 6).map((it) => !!it.multiple),
      bestInputAccept: input?.accept || "",
      bestInputMultiple: !!input?.multiple,
      visibleAttachmentButton: !!findAttachmentButton(),
      visibleUploadMenuItem: !!findUploadMenuItem(),
      bridgeAvailable: !!window.AIHubNativeFiles,
      stagedCount: window.AIHubNativeFiles?.count?.() || 0,
      lastAttachedCount: Number(state.lastAttachedCount || 0),
      lastAttachedAgeMs: state.lastAttachedAt ? Math.max(0, Date.now() - state.lastAttachedAt) : -1,
      lastInputAccept: state.lastInputAccept || "",
      lastInputMultiple: !!state.lastInputMultiple,
      lastStrategy: state.lastStrategy || "",
      lastPrepare: state.lastPrepare || "",
      pathHash: simpleHash(location.pathname)
    };
  };
})();

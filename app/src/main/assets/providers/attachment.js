(() => {
  const api = window.__AIHUB__;
  if (!api) return;

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

  const attachmentButtonSelectors = [
    "[data-testid='composer-plus-btn']",
    "[data-testid*='attach' i]",
    "[data-testid*='upload' i]",
    "button[aria-label*='attach' i]",
    "button[aria-label*='upload' i]",
    "button[aria-label*='add file' i]",
    "button[aria-label*='add photo' i]",
    "button[aria-label*='photo' i]",
    "button[title*='attach' i]",
    "button[title*='upload' i]",
    "[role='button'][aria-label*='attach' i]",
    "[role='button'][aria-label*='upload' i]"
  ];

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

  const b64ToBytes = (value) => {
    const raw = atob(value || "");
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  };

  api.prepareAttachmentInput = () => {
    if (bestFileInput()) return "ready";
    const button = findAttachmentButton();
    if (!button) return "not-found";
    button.click();
    return "opened-menu";
  };

  api.attachStagedFiles = () => {
    const bridge = window.AIHubNativeFiles;
    if (!bridge || typeof bridge.count !== "function") return "no-bridge";

    const input = bestFileInput();
    if (!input) return "no-input";

    const total = Number(bridge.count()) || 0;
    if (total <= 0) return "no-files";

    const data = new DataTransfer();
    const max = input.multiple ? total : Math.min(total, 1);

    for (let i = 0; i < max; i++) {
      const parts = [];
      const chunks = Number(bridge.chunkCount(i)) || 0;
      for (let part = 0; part < chunks; part++) {
        const encoded = bridge.chunk(i, part);
        if (encoded) parts.push(b64ToBytes(encoded));
      }
      const name = String(bridge.name(i) || `attachment-${i + 1}`);
      const mime = String(bridge.mime(i) || "application/octet-stream");
      data.items.add(new File(parts, name, { type: mime, lastModified: Date.now() }));
    }

    try {
      input.files = data.files;
    } catch (_) {
      return "assign-failed";
    }

    input.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
    input.dispatchEvent(new Event("change", { bubbles: true, composed: true }));
    return `attached:${data.files.length}`;
  };

  api.openAttachmentPicker = () => {
    const input = bestFileInput();
    if (input) {
      input.click();
      return "opened-input";
    }
    const button = findAttachmentButton();
    if (!button) return "not-found";
    button.click();
    return "opened-button";
  };

  api.attachmentProbe = () => {
    const inputs = fileInputs();
    return {
      inputCount: inputs.length,
      accepts: inputs.slice(0, 6).map((input) => input.accept || "*/*"),
      multiples: inputs.slice(0, 6).map((input) => !!input.multiple),
      visibleAttachmentButton: !!findAttachmentButton(),
      bridgeAvailable: !!window.AIHubNativeFiles,
      stagedCount: window.AIHubNativeFiles?.count?.() || 0,
      path: location.pathname
    };
  };
})();

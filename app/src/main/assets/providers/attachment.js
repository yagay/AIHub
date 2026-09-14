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

  const clickUploadMenuItem = () => {
    const candidates = Array.from(document.querySelectorAll("button, [role='menuitem'], [role='option'], [role='button']"));
    const rx = /(upload|attach|add\s+(photos?|files?)|photos?\s*&\s*files?|上传|附件|添加图片|添加文件|图片|文件)/i;
    const item = candidates.find((node) => visible(node) && rx.test((node.innerText || node.textContent || node.getAttribute?.("aria-label") || "").trim()));
    if (!item) return false;
    item.click();
    return true;
  };

  const clickBestInput = () => {
    const input = bestFileInput();
    if (!input) return false;
    input.click();
    return true;
  };

  api.openAttachmentPicker = () => {
    if (clickBestInput()) return "opened-input";

    const button = findAttachmentButton();
    if (!button) return "not-found";
    button.click();

    [80, 180, 350, 650].forEach((delay) => {
      setTimeout(() => {
        if (clickBestInput()) return;
        clickUploadMenuItem();
        setTimeout(() => { clickBestInput(); }, 80);
      }, delay);
    });
    return "opened-button";
  };

  api.attachmentProbe = () => {
    const inputs = fileInputs();
    return {
      inputCount: inputs.length,
      accepts: inputs.slice(0, 6).map((input) => input.accept || "*/*"),
      multiples: inputs.slice(0, 6).map((input) => !!input.multiple),
      visibleAttachmentButton: !!findAttachmentButton(),
      path: location.pathname
    };
  };
})();

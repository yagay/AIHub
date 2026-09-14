window.__AIHUB_CONFIG__ = {
  homeUrl: "https://claude.ai/new",
  inputSelectors: ["div.ProseMirror[contenteditable='true']", "[contenteditable='true'][data-placeholder]", "fieldset [contenteditable='true']"],
  sendSelectors: ["button[aria-label='Send message']", "button[aria-label*='Send']"],
  responseSelectors: ["[data-is-streaming] .font-claude-message", ".font-claude-message", "[data-testid*='assistant']"],
  stopSelectors: ["button[aria-label*='Stop']", "button[data-testid*='stop']"],
  newChatSelectors: ["a[href='/new']", "button[aria-label*='New chat']"]
};

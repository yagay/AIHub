window.__AIHUB_CONFIG__ = {
  homeUrl: "https://gemini.google.com/app",
  inputSelectors: ["rich-textarea [contenteditable='true']", ".ql-editor[contenteditable='true']", "textarea"],
  sendSelectors: ["button[aria-label*='Send message']", "button.send-button"],
  responseSelectors: ["message-content", ".model-response-text", "model-response .markdown"],
  stopSelectors: ["button[aria-label*='Stop response']", "button[aria-label*='Stop']"],
  newChatSelectors: ["a[href='/app']", "button[aria-label*='New chat']"]
};

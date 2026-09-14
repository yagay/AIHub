window.__AIHUB_CONFIG__ = {
  homeUrl: "https://chatgpt.com/",
  inputSelectors: ["#prompt-textarea", "textarea[data-id='root']", "textarea[placeholder*='Message']", "[contenteditable='true'][data-placeholder]"],
  sendSelectors: ["button[data-testid='send-button']", "button[aria-label*='Send']"],
  responseSelectors: ["[data-message-author-role='assistant']", "article .markdown"],
  stopSelectors: ["button[data-testid='stop-button']", "button[aria-label*='Stop']"],
  newChatSelectors: ["a[data-testid='create-new-chat-button']", "button[aria-label*='New chat']", "a[href='/']"]
};

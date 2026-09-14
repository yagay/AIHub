window.__AIHUB_CONFIG__ = {
  homeUrl: "https://gemini.google.com/app",
  inputSelectors: [
    "rich-textarea div[contenteditable='true']",
    "div.ql-editor[contenteditable='true']",
    "div[contenteditable='true'][aria-label*='Enter a prompt' i]",
    "div[contenteditable='true']",
    "textarea"
  ],
  loggedInSelectors: [
    "button[aria-label*='New chat' i]",
    "a[href='/app']",
    "input-area-v2"
  ],
  sendSelectors: [
    "button[aria-label*='Send message' i]",
    "button[aria-label='Send']",
    "button[mattooltip*='Send' i]",
    "button.send-button"
  ],
  responseSelectors: [
    "message-content.model-response-text",
    "model-response",
    "div[class*='model-response-text']",
    "div[class*='model-response']"
  ],
  stopSelectors: [
    "button[aria-label*='Stop response' i]",
    "button[aria-label*='Stop' i]"
  ],
  newChatSelectors: [
    "button[aria-label*='New chat' i]",
    "expandable-button[aria-label*='New chat' i] button",
    "a[href='/app']"
  ]
};

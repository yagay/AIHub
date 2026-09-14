window.__AIHUB_CONFIG__ = {
  homeUrl: "https://chat.deepseek.com/",
  verifySubmission: true,
  inputSelectors: [
    "textarea[placeholder*='Message DeepSeek' i]",
    "textarea._27c9245",
    "textarea.ds-scroll-area",
    "textarea",
    "#chat-input",
    "[contenteditable='true'][role='textbox']"
  ],
  loggedInSelectors: [
    "textarea[placeholder*='Message DeepSeek' i]",
    "textarea.ds-scroll-area",
    "div.ds-button--primary.ds-button--circle",
    "a[href*='/chat/']"
  ],
  sendSelectors: [
    "div.ds-button.ds-button--primary.ds-button--filled.ds-button--circle:not(.ds-button--disabled)",
    "div.ds-button--primary.ds-button--circle:not(.ds-button--disabled)",
    "div[role='button'].ds-button--primary:not(.ds-button--disabled)"
  ],
  responseSelectors: [
    "div[class*='message-content']",
    "div[class*='markdown-body']",
    "div.ds-markdown",
    "div.ds-assistant-message-main-content div.ds-markdown",
    "div.ds-assistant-message-main-content"
  ],
  turnSelectors: [
    "div.ds-message",
    "[class*='ds-message']"
  ],
  assistantMarkerSelectors: [
    "div.ds-assistant-message-main-content",
    "div.ds-think-content",
    "[class*='assistant-message']",
    "[class*='think-content']"
  ],
  stopSelectors: [
    "div.ds-button--primary.ds-button--circle:not(.ds-button--disabled)"
  ],
  newChatSelectors: [
    "button[aria-label*='New chat' i]",
    "div[role='button'][aria-label*='New chat' i]",
    "a[href='/']"
  ]
};

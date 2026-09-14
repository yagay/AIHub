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
  modelSelectors: [
    "button[aria-label*='model' i]",
    "[role='button'][aria-label*='model' i]",
    "[data-testid*='model' i]"
  ],
  searchSelectors: [
    "button[aria-label*='search' i]",
    "[role='button'][aria-label*='search' i]",
    "[data-testid*='search' i]"
  ],
  reasoningSelectors: [
    "button[aria-label*='deepthink' i]",
    "[role='button'][aria-label*='deepthink' i]",
    "button[aria-label*='think' i]",
    "[data-testid*='think' i]"
  ],
  retrySelectors: [
    "button[aria-label*='regenerate' i]",
    "button[aria-label*='retry' i]",
    "[role='button'][aria-label*='regenerate' i]"
  ],
  continueSelectors: [
    "button[aria-label*='continue' i]",
    "[role='button'][aria-label*='continue' i]"
  ],
  copyButtonSelectors: [
    "button[aria-label*='copy' i]",
    "[role='button'][aria-label*='copy' i]"
  ],
  editSelectors: [
    "button[aria-label*='edit' i]",
    "[role='button'][aria-label*='edit' i]"
  ],
  historySelectors: [
    "a[href^='/a/chat/s/']",
    "a[href*='/chat/s/']",
    "nav a[href*='/chat/']"
  ],
  conversationMenuSelectors: [
    "button[aria-label*='chat options' i]",
    "[role='button'][aria-label*='chat options' i]",
    "button[aria-haspopup='menu'][aria-label*='conversation' i]"
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

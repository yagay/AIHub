window.__AIHUB_CONFIG__ = {
  homeUrl: "https://chat.deepseek.com/",
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
    "div.ds-markdown",
    "div.markdown-body",
    "div[class*='markdown']",
    "div[class*='answer']",
    "div[class*='message-content']"
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

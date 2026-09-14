window.__AIHUB_CONFIG__ = {
  homeUrl: "https://chatgpt.com/",
  inputSelectors: [
    "#prompt-textarea[contenteditable='true']",
    "#prompt-textarea",
    "[data-testid='prompt-textarea']",
    "textarea[name='prompt-textarea']",
    "div[contenteditable='true'][role='textbox'][aria-label*='Chat' i]",
    "div[contenteditable='true'].ProseMirror",
    "textarea[aria-label*='Chat' i]",
    "textarea[placeholder*='Ask' i]",
    "textarea[data-id='root']",
    "[contenteditable='true'][data-placeholder]"
  ],
  loggedInSelectors: [
    "#prompt-textarea",
    "[data-testid='composer-plus-btn']",
    "a[data-testid='create-new-chat-button']",
    "#history a[href^='/c/']",
    "a[href^='/c/']",
    "button[data-testid='model-switcher-dropdown-button']"
  ],
  sendSelectors: [
    "#composer-submit-button",
    "button[data-testid='send-button']",
    "button[aria-label='Send prompt']",
    "button[aria-label='发送提示']",
    "button[aria-label*='Send dictated message' i]",
    "button.composer-submit-btn",
    "form button[type='submit']"
  ],
  responseSelectors: [
    "[data-message-author-role='assistant'] .markdown",
    "div[data-message-author-role='assistant']",
    "[data-role='assistant']",
    "[data-message-author='assistant']",
    ".agent-turn",
    "article[data-testid*='conversation-turn'] .markdown"
  ],
  stopSelectors: [
    "button[data-testid='stop-button']",
    "button[aria-label*='Stop' i]"
  ],
  newChatSelectors: [
    "a[data-testid='create-new-chat-button']",
    "a[aria-label='New chat']",
    "button[aria-label*='New chat' i]",
    "a[href='/']"
  ]
};

window.__AIHUB_CONFIG__ = {
  homeUrl: "https://chatgpt.com/",
  inputSelectors: [
    "#prompt-textarea[contenteditable='true']",
    "#prompt-textarea",
    "[data-testid='prompt-textarea']",
    "textarea[name='prompt-textarea']",
    "textarea[data-id='prompt-textarea']",
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
    "#history a[href^='/uc/']",
    "a[href^='/c/']",
    "a[href^='/uc/']",
    "button[data-testid='model-switcher-dropdown-button']"
  ],
  sendSelectors: [
    "#composer-submit-button",
    "button[data-testid='send-button']",
    "button[data-testid*='composer-send']",
    "button[aria-label='Send prompt']",
    "button[aria-label='发送提示']",
    "button[aria-label*='Send dictated message' i]",
    "button.composer-submit-btn",
    "form button[type='submit']"
  ],
  responseSelectors: [
    "article[data-testid^='conversation-turn'][data-message-author-role='assistant']",
    "article[data-testid^='conversation-turn'][data-turn='assistant']",
    "article[data-testid^='conversation-turn'] [data-message-author-role='assistant']",
    "article[data-testid^='conversation-turn'] [data-turn='assistant']",
    "div[data-testid^='conversation-turn'] [data-message-author-role='assistant']",
    "div[data-testid^='conversation-turn'] [data-turn='assistant']",
    "section[data-testid^='conversation-turn'] [data-message-author-role='assistant']",
    "section[data-testid^='conversation-turn'] [data-turn='assistant']",
    "[data-message-author-role='assistant'] .markdown",
    "[data-turn='assistant'] .markdown",
    "[data-message-author-role='assistant']",
    "[data-turn='assistant']",
    ".agent-turn .markdown",
    ".agent-turn"
  ],
  responseContentSelectors: [
    "[data-message-author-role='assistant'] .markdown",
    "[data-turn='assistant'] .markdown",
    ".markdown",
    ".prose",
    "[class*='markdown']",
    "[data-message-author-role='assistant']",
    "[data-turn='assistant']"
  ],
  turnSelectors: [
    "article[data-testid^='conversation-turn']",
    "div[data-testid^='conversation-turn']",
    "section[data-testid^='conversation-turn']",
    "article[data-message-author-role]",
    "div[data-message-author-role]",
    "section[data-message-author-role]",
    "article[data-turn]",
    "div[data-turn]",
    "section[data-turn]"
  ],
  assistantMarkerSelectors: [
    "[data-message-author-role='assistant']",
    "[data-turn='assistant']"
  ],
  copyButtonSelectors: [
    "button[data-testid='copy-turn-action-button']",
    "button[aria-label='Copy']",
    "button[aria-label*='Copy' i]"
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

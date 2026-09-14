window.__AIHUB_CONFIG__ = {
  homeUrl: "https://chatgpt.com/",
  inputSelectors: [
    "#prompt-textarea[contenteditable='true']",
    "div#prompt-textarea",
    "textarea#prompt-textarea",
    "div[contenteditable='true'].ProseMirror",
    "textarea[data-id='root']",
    "[contenteditable='true'][data-placeholder]"
  ],
  loggedInSelectors: [
    "a[data-testid='create-new-chat-button']",
    "a[href^='/c/']",
    "button[data-testid='model-switcher-dropdown-button']"
  ],
  sendSelectors: [
    "button[data-testid='send-button']",
    "button[aria-label='Send prompt']",
    "button[aria-label*='Send']",
    "form button[type='submit']"
  ],
  responseSelectors: [
    "div[data-message-author-role='assistant']",
    "article[data-testid*='conversation-turn']"
  ],
  stopSelectors: [
    "button[data-testid='stop-button']",
    "button[aria-label*='Stop']"
  ],
  newChatSelectors: [
    "a[data-testid='create-new-chat-button']",
    "a[aria-label='New chat']",
    "button[aria-label*='New chat']",
    "a[href='/']"
  ]
};

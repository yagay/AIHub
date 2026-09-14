window.__AIHUB_CONFIG__ = {
  homeUrl: "https://gemini.google.com/app",
  inputSelectors: [
    "rich-textarea div[contenteditable='true']",
    "div.ql-editor[contenteditable='true']",
    "div[contenteditable='true'][aria-label*='Enter a prompt' i]",
    "[contenteditable='true'][role='textbox']",
    "div[contenteditable='true']",
    "textarea"
  ],
  loggedInSelectors: [
    "input-area-v2",
    "rich-textarea",
    "button[aria-label*='New chat' i]",
    "a[href='/app']"
  ],
  attachmentButtonSelectors: [
    "button[aria-label='Upload & tools']",
    "button[aria-label='Open upload file menu']",
    "button[aria-label*='Upload & tools' i]",
    "button[aria-label*='upload file menu' i]",
    "button[aria-label*='Upload' i]"
  ],
  uploadMenuItemSelectors: [
    "images-files-uploader[data-test-id='uploader-images-files-button-advanced']",
    "[data-test-id='local-images-files-uploader-icon']",
    "[data-test-id='uploader-images-files-button-advanced']",
    "images-files-uploader",
    "[role='menuitem'] [data-test-id*='uploader' i]"
  ],
  sendSelectors: [
    "button[aria-label='Send message']",
    "button[aria-label*='Send' i]",
    "button[mattooltip*='Send' i]",
    ".send-button",
    "button.send-button",
    "gem-icon-button.submit[aria-disabled='false']"
  ],
  responseSelectors: [
    "message-content.model-response-text",
    "model-response message-content",
    "model-response .model-response-text",
    "model-response .message-content",
    "model-response .response-content",
    "model-response",
    "message-content",
    ".model-response-text",
    ".response-content",
    "div[class*='model-response-text']",
    "div[class*='model-response']"
  ],
  modelSelectors: [
    "button[aria-label*='model' i]",
    "[data-test-id='model-selector']",
    "[role='button'][aria-label*='model' i]"
  ],
  toolsSelectors: [
    "button[aria-label='Upload & tools']",
    "button[aria-label*='tools' i]",
    "button[aria-label*='tool' i]"
  ],
  searchSelectors: [
    "button[aria-label*='search' i]",
    "[data-test-id*='search' i]"
  ],
  reasoningSelectors: [
    "button[aria-label*='thinking' i]",
    "button[aria-label*='reasoning' i]",
    "[data-test-id*='thinking' i]"
  ],
  deepResearchSelectors: [
    "button[aria-label*='deep research' i]",
    "[data-test-id*='deep-research' i]",
    "[data-test-id*='research' i]"
  ],
  imageGenerationSelectors: [
    "button[aria-label*='image' i]",
    "[data-test-id*='image-generation' i]"
  ],
  retrySelectors: [
    "button[aria-label='Redo']",
    "button[aria-label*='redo' i]",
    "button[aria-label*='regenerate' i]",
    "button[data-test-id*='redo' i]"
  ],
  copyButtonSelectors: [
    "button[aria-label='Copy']",
    "button[aria-label*='copy' i]"
  ],
  editSelectors: [
    "button[aria-label*='edit' i]",
    "button[data-test-id*='edit' i]"
  ],
  historySelectors: [
    "a[href*='/app/']",
    "nav a[href*='/app/']"
  ],
  voiceSelectors: [
    "button[aria-label*='microphone' i]",
    "button[aria-label*='voice' i]",
    "[data-test-id*='mic' i]"
  ],
  stopSelectors: [
    "button[aria-label*='Stop response' i]",
    "button[aria-label*='Stop' i]",
    "[aria-busy='true']"
  ],
  newChatSelectors: [
    "button[aria-label*='New chat' i]",
    "expandable-button[aria-label*='New chat' i] button",
    "a[href='/app']"
  ]
};

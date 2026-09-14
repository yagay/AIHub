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

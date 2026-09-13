(() => {
  const NATIVE_APP = "aihub";
  let port = null;
  let dirtyTimer = 0;

  const connect = () => {
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
      port.onMessage.addListener(handleCommand);
      port.onDisconnect.addListener(() => {
        port = null;
        setTimeout(connect, 500);
      });
      post({ type: "ready", url: location.href });
    } catch (_) {
      setTimeout(connect, 500);
    }
  };

  const post = message => {
    try {
      if (port) port.postMessage(message);
    } catch (_) {}
  };

  const selectors = (command, key) => {
    const value = command && command.selectors && command.selectors[key];
    return Array.isArray(value) ? value : [];
  };

  const visible = element => !!element && !element.disabled &&
      !!(element.offsetWidth || element.offsetHeight || element.getClientRects().length);

  const firstVisible = list => {
    for (const selector of list) {
      try {
        const element = document.querySelector(selector);
        if (visible(element)) return element;
      } catch (_) {}
    }
    return null;
  };

  const semanticButton = words => {
    const lowered = words.map(word => String(word).toLowerCase());
    for (const element of document.querySelectorAll('button,[role="button"],a')) {
      if (!visible(element)) continue;
      const text = [element.getAttribute('aria-label'), element.getAttribute('title'), element.textContent]
        .filter(Boolean).join(' ').trim().toLowerCase();
      if (lowered.some(word => text.includes(word))) return element;
    }
    return null;
  };

  const dispatchInput = (input, value) => {
    try {
      input.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        composed: true,
        inputType: 'insertText',
        data: value
      }));
    } catch (_) {
      input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    }
    input.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
  };

  const setComposerText = (input, value) => {
    input.focus();
    if ('value' in input) {
      let target = input;
      let setter = null;
      while (target && !setter) {
        const descriptor = Object.getOwnPropertyDescriptor(target, 'value');
        if (descriptor && descriptor.set) setter = descriptor.set;
        target = Object.getPrototypeOf(target);
      }
      if (setter) setter.call(input, value);
      else input.value = value;
      dispatchInput(input, value);
      return;
    }

    // ProseMirror and other contenteditable editors usually need an editing operation rather than
    // a raw textContent assignment so their framework state observes the change.
    let inserted = false;
    try {
      const selection = getSelection();
      const range = document.createRange();
      range.selectNodeContents(input);
      selection.removeAllRanges();
      selection.addRange(range);
      inserted = document.execCommand('insertText', false, value);
    } catch (_) {}
    if (!inserted) input.textContent = value;
    dispatchInput(input, value);
  };

  const clickAction = (configured, words) => {
    const element = firstVisible(configured) || semanticButton(words);
    if (!element) return false;
    element.click();
    return true;
  };

  const collectMessages = command => {
    const roles = new Map();
    const add = (list, role) => {
      for (const selector of list) {
        let nodes = [];
        try { nodes = document.querySelectorAll(selector); } catch (_) { continue; }
        for (const node of nodes) {
          if (!roles.has(node)) roles.set(node, role);
        }
      }
    };
    add(selectors(command, 'userMessage'), 'user');
    add(selectors(command, 'assistantMessage'), 'assistant');

    const ordered = [...roles.entries()]
      .sort((a, b) => {
        if (a[0] === b[0]) return 0;
        const position = a[0].compareDocumentPosition(b[0]);
        return position & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1;
      })
      .map(([node, role]) => ({
        role,
        text: (node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim()
      }))
      .filter(item => item.text.length > 0);

    const deduped = [];
    for (const item of ordered) {
      const previous = deduped[deduped.length - 1];
      if (previous && previous.role === item.role && previous.text === item.text) continue;
      deduped.push(item);
    }
    return deduped.slice(-100);
  };

  const findComposer = command => firstVisible(selectors(command, 'input')) ||
    [...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].find(visible) || null;

  const handleCommand = command => {
    if (!command || typeof command !== 'object') return;
    const action = String(command.action || '');
    const requestId = command.requestId || '';
    const respond = payload => post(Object.assign({ type: 'response', action, requestId }, payload));

    if (action === 'probe') {
      const input = findComposer(command);
      respond({
        ok: !!input,
        composer: !!input,
        messageMirror: selectors(command, 'userMessage').length > 0 || selectors(command, 'assistantMessage').length > 0,
        url: location.href
      });
      return;
    }

    if (action === 'send') {
      const input = findComposer(command);
      if (!input) {
        respond({ ok: false, stage: 'input' });
        return;
      }
      setComposerText(input, String(command.text || ''));
      const clicked = clickAction(selectors(command, 'send'), ['send', 'submit', '发送', '提交', 'ask']);
      if (!clicked) {
        input.dispatchEvent(new KeyboardEvent('keydown', {
          key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
        }));
        input.dispatchEvent(new KeyboardEvent('keyup', {
          key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
        }));
      }
      respond({ ok: true, method: clicked ? 'button' : 'enter' });
      return;
    }

    if (action === 'newChat') {
      respond({ ok: clickAction(selectors(command, 'newChat'), ['new chat', 'new conversation', '新对话', '新聊天']) });
      return;
    }

    if (action === 'stop') {
      respond({ ok: clickAction(selectors(command, 'stop'), ['stop generating', 'stop', '停止生成', '停止']) });
      return;
    }

    if (action === 'attach') {
      respond({ ok: clickAction(selectors(command, 'attachment'), ['attach', 'upload', 'file', '附件', '上传']) });
      return;
    }

    if (action === 'sync') {
      respond({ ok: true, messages: collectMessages(command), url: location.href });
    }
  };

  const markDirty = () => {
    clearTimeout(dirtyTimer);
    dirtyTimer = setTimeout(() => post({ type: 'dirty', url: location.href }), 180);
  };

  const observer = new MutationObserver(markDirty);
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  addEventListener('popstate', () => post({ type: 'location', url: location.href }));
  addEventListener('hashchange', () => post({ type: 'location', url: location.href }));
  connect();
})();

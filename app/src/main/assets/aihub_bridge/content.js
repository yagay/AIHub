(() => {
  const NATIVE_APP = "aihub";
  const APP_CLASS = "aihub-native-app-mode";
  const STYLE_ID = "aihub-native-app-style";
  let port = null;

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

  const findComposer = command => firstVisible(selectors(command, 'input')) ||
    [...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].find(visible) || null;

  const cssList = command => {
    const all = [
      ...selectors(command, 'input'),
      ...selectors(command, 'send'),
      ...selectors(command, 'stop'),
      ...selectors(command, 'attachment')
    ];
    const unique = [...new Set(all.filter(Boolean))];
    return unique.map(selector => `html.${APP_CLASS} ${selector}`).join(',\n');
  };

  const applyPresentation = (command, appMode) => {
    let style = document.getElementById(STYLE_ID);
    if (!style) {
      style = document.createElement('style');
      style.id = STYLE_ID;
      (document.head || document.documentElement).appendChild(style);
    }

    const targets = cssList(command);
    style.textContent = targets ? `${targets} {
      opacity: 0 !important;
      pointer-events: none !important;
    }` : '';
    document.documentElement.classList.toggle(APP_CLASS, !!appMode);
    return true;
  };

  const handleCommand = command => {
    if (!command || typeof command !== 'object') return;
    const action = String(command.action || '');
    const requestId = command.requestId || '';
    const respond = payload => post(Object.assign({ type: 'response', action, requestId }, payload));

    if (action === 'probe') {
      const input = findComposer(command);
      respond({ ok: !!input, composer: !!input, url: location.href });
      return;
    }

    if (action === 'presentation') {
      try {
        respond({ ok: applyPresentation(command, !!command.appMode), url: location.href });
      } catch (_) {
        respond({ ok: false, url: location.href });
      }
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
    }
  };

  addEventListener('popstate', () => post({ type: 'location', url: location.href }));
  addEventListener('hashchange', () => post({ type: 'location', url: location.href }));
  connect();
})();

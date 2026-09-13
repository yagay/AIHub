(() => {
  const NATIVE_APP = "aihub";
  const APP_CLASS = "aihub-native-app-mode";
  const STYLE_ID = "aihub-native-app-style";
  const COMPOSER_MARK = "data-aihub-composer-shell";
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

  const array = value => Array.isArray(value) ? value.filter(Boolean) : [];
  const selectors = (command, key) => array(command && command.selectors && command.selectors[key]);

  const visible = element => !!element && !element.disabled &&
      !!(element.offsetWidth || element.offsetHeight || element.getClientRects().length);

  const existing = (list, pick = 'first') => {
    for (const selector of array(list)) {
      try {
        const nodes = [...document.querySelectorAll(selector)].filter(element => !element.disabled);
        if (!nodes.length) continue;
        return pick === 'last' ? nodes[nodes.length - 1] : nodes[0];
      } catch (_) {}
    }
    return null;
  };

  const semanticControl = (words, pick = 'first') => {
    const lowered = array(words).map(word => String(word).trim().toLowerCase()).filter(Boolean);
    if (!lowered.length) return null;

    const candidates = [];
    for (const element of document.querySelectorAll('button,[role="button"],a,[tabindex]')) {
      if (element.disabled) continue;
      const aria = (element.getAttribute('aria-label') || '').trim().toLowerCase();
      const title = (element.getAttribute('title') || '').trim().toLowerCase();
      const testId = (element.getAttribute('data-testid') || '').trim().toLowerCase();
      const text = (element.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
      let score = visible(element) ? 2 : 0;
      for (const word of lowered) {
        if (aria === word || title === word || text === word) score += 12;
        else if (aria.includes(word)) score += 9;
        else if (title.includes(word) || testId.includes(word)) score += 7;
        else if (text.includes(word)) score += 4;
      }
      if (score > 0) candidates.push({ element, score });
    }
    if (!candidates.length) return null;
    const bestScore = Math.max(...candidates.map(item => item.score));
    const best = candidates.filter(item => item.score === bestScore);
    return (pick === 'last' ? best[best.length - 1] : best[0]).element;
  };

  const temporarilyClickable = element => {
    if (!element) return false;
    const changed = [];
    let node = element;
    while (node && node !== document.documentElement) {
      try {
        const styleText = node.getAttribute('style');
        const computed = getComputedStyle(node);
        let touched = false;
        if (computed.display === 'none') {
          node.style.setProperty('display', 'block', 'important');
          touched = true;
        }
        if (computed.visibility === 'hidden') {
          node.style.setProperty('visibility', 'visible', 'important');
          touched = true;
        }
        if (computed.pointerEvents === 'none') {
          node.style.setProperty('pointer-events', 'auto', 'important');
          touched = true;
        }
        if (touched) {
          node.style.setProperty('opacity', '0', 'important');
          changed.push([node, styleText]);
        }
      } catch (_) {}
      node = node.parentElement;
    }

    try {
      element.click();
    } catch (_) {
      return false;
    } finally {
      setTimeout(() => {
        for (const [target, oldStyle] of changed) {
          try {
            if (oldStyle == null) target.removeAttribute('style');
            else target.setAttribute('style', oldStyle);
          } catch (_) {}
        }
      }, 0);
    }
    return true;
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

  const clickAction = (configured, words, pick = 'first') => {
    const element = existing(configured, pick) || semanticControl(words, pick);
    return temporarilyClickable(element);
  };

  const findComposer = command => existing(selectors(command, 'input')) ||
    [...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')]
      .find(element => !element.disabled) || null;

  const prefixSelectors = list => array(list)
    .map(selector => `html.${APP_CLASS} ${selector}`)
    .join(',\n');

  const markComposerShell = command => {
    for (const old of document.querySelectorAll(`[${COMPOSER_MARK}]`)) old.removeAttribute(COMPOSER_MARK);
    const input = findComposer(command);
    if (!input) return;
    let shell = null;
    try {
      shell = input.closest("form,[data-testid*='composer'],[data-testid*='prompt']");
    } catch (_) {}
    if (shell && shell !== document.body && shell !== document.documentElement) {
      shell.setAttribute(COMPOSER_MARK, 'true');
    }
  };

  const applyPresentation = (command, appMode) => {
    let style = document.getElementById(STYLE_ID);
    if (!style) {
      style = document.createElement('style');
      style.id = STYLE_ID;
      (document.head || document.documentElement).appendChild(style);
    }

    markComposerShell(command);
    const hiddenChrome = prefixSelectors(command.appHide);
    const hiddenControls = prefixSelectors([
      ...selectors(command, 'input'),
      ...selectors(command, 'send'),
      ...selectors(command, 'stop'),
      ...selectors(command, 'attachment')
    ]);
    const chromeRules = [
      hiddenChrome,
      `html.${APP_CLASS} [${COMPOSER_MARK}='true']`
    ].filter(Boolean).join(',\n');

    style.textContent = `${chromeRules} {
      display: none !important;
    }
    ${hiddenControls} {
      opacity: 0 !important;
      pointer-events: none !important;
    }`;
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

    if (action === 'uiAction') {
      const pick = command.actionPick === 'last' ? 'last' : 'first';
      const clicked = clickAction(command.actionSelectors, command.actionKeywords, pick);
      respond({ ok: clicked, control: command.uiActionId || '', url: location.href });
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

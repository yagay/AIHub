package com.yagay.aihub.web;

import com.yagay.aihub.model.ProviderSpec;

import java.util.List;

/** Shared DOM engine used by every generic web provider. */
public final class DomBridge {
    private DomBridge() {}

    public static String send(ProviderSpec spec, String text) {
        return """
            (()=>{
              const value=%s,inputSelectors=%s,sendSelectors=%s;
              const visible=e=>!!e&&!e.disabled&&!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
              const usable=e=>!!e&&!e.disabled&&(visible(e)||!!e.closest('[data-aihub-hidden="1"]'));
              const pick=selectors=>{for(const s of selectors){try{const e=document.querySelector(s);if(usable(e))return e;}catch(_){}}return null;};
              const score=e=>{if(!usable(e))return-1;const r=e.getBoundingClientRect();let n=0;const tag=(e.tagName||'').toLowerCase();if(tag==='textarea')n+=1000;if(e.isContentEditable)n+=900;if(e.getAttribute('role')==='textbox')n+=800;if(r.top>innerHeight*.45)n+=500;return n;};
              const inputs=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(usable).sort((a,b)=>score(b)-score(a));
              const input=pick(inputSelectors)||inputs[0];if(!input)return JSON.stringify({ok:false,stage:'input'});
              input.focus();
              if('value' in input){let p=input,setter=null;while(p&&!setter){const d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)setter=d.set;p=Object.getPrototypeOf(p);}if(setter)setter.call(input,value);else input.value=value;}else input.textContent=value;
              input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));input.dispatchEvent(new Event('change',{bubbles:true}));
              const semantic=[...document.querySelectorAll('button,[role="button"]')].filter(usable).find(e=>{const t=[e.getAttribute('aria-label'),e.getAttribute('title'),e.textContent].filter(Boolean).join(' ').toLowerCase();return ['send','submit','发送','提交','ask'].some(w=>t.includes(w));});
              const button=pick(sendSelectors)||semantic;if(button){button.click();return JSON.stringify({ok:true,method:'button'});}
              input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));input.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));return JSON.stringify({ok:true,method:'enter'});
            })()
            """.formatted(jsString(text), jsArray(spec.inputSelectors()), jsArray(spec.sendSelectors()));
    }

    public static String newChat(ProviderSpec spec) {
        return click(spec.newChatSelectors(), List.of("new chat", "new conversation", "新对话", "新聊天"));
    }

    public static String stop(ProviderSpec spec) {
        return click(spec.stopSelectors(), List.of("stop generating", "stop", "停止生成", "停止"));
    }

    /**
     * Hides common web chrome only after a chat composer is found. Login/verification pages therefore
     * stay intact. Elements are tagged and hidden, never removed, so commands can still operate them.
     */
    public static String appMode(ProviderSpec spec, boolean enabled) {
        return """
            (()=>{
              const enabled=%s,inputSelectors=%s,ATTR='data-aihub-hidden',STYLE='aihub-native-mode';
              const clear=()=>document.querySelectorAll('['+ATTR+']').forEach(e=>e.removeAttribute(ATTR));
              const shutdown=()=>{if(window.__aihubObserver){window.__aihubObserver.disconnect();delete window.__aihubObserver;}if(window.__aihubTimer){clearTimeout(window.__aihubTimer);delete window.__aihubTimer;}clear();const s=document.getElementById(STYLE);if(s)s.remove();};
              if(!enabled){shutdown();return 'off';}
              let style=document.getElementById(STYLE);if(!style){style=document.createElement('style');style.id=STYLE;style.textContent='[data-aihub-hidden="1"]{display:none!important}';(document.head||document.documentElement).appendChild(style);}
              const visible=e=>!!e&&!e.disabled&&!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
              const pick=()=>{for(const s of inputSelectors){try{const e=document.querySelector(s);if(visible(e))return e;}catch(_){}}const list=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(visible);return list.sort((a,b)=>b.getBoundingClientRect().top-a.getBoundingClientRect().top)[0]||null;};
              const hide=e=>{if(e&&e!==document.body&&e!==document.documentElement)e.setAttribute(ATTR,'1');};
              const apply=()=>{clear();const input=pick();if(!input)return false;let composer=input.parentElement;for(let e=input.parentElement,i=0;e&&e!==document.body&&i<7;e=e.parentElement,i++){const r=e.getBoundingClientRect();if(r.bottom>=innerHeight*.70&&r.height>20&&r.height<=Math.min(280,innerHeight*.38)&&r.width>=innerWidth*.45)composer=e;}hide(composer);for(const e of document.querySelectorAll('aside,nav,[role="navigation"],header')){if(!visible(e)||e.contains(input))continue;const r=e.getBoundingClientRect();const tag=(e.tagName||'').toLowerCase();const side=(tag==='aside'||tag==='nav'||e.getAttribute('role')==='navigation')&&r.height>=innerHeight*.30&&r.width<=innerWidth*.65;const top=(tag==='header'||r.top<12)&&r.height<=180&&r.width>=innerWidth*.55;if(side||top)hide(e);}return true;};
              if(window.__aihubObserver)window.__aihubObserver.disconnect();const schedule=()=>{if(window.__aihubTimer)clearTimeout(window.__aihubTimer);window.__aihubTimer=setTimeout(apply,150);};window.__aihubObserver=new MutationObserver(schedule);window.__aihubObserver.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['class','style','hidden','aria-hidden']});return apply()?'on':'waiting';
            })()
            """.formatted(enabled ? "true" : "false", jsArray(spec.inputSelectors()));
    }

    private static String click(List<String> selectors, List<String> words) {
        return """
            (()=>{const selectors=%s,words=%s;const visible=e=>!!e&&!e.disabled&&!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);const usable=e=>!!e&&!e.disabled&&(visible(e)||!!e.closest('[data-aihub-hidden="1"]'));for(const s of selectors){try{const e=document.querySelector(s);if(usable(e)){e.click();return 'selector';}}catch(_){}}for(const e of document.querySelectorAll('button,[role="button"],a')){if(!usable(e))continue;const t=[e.getAttribute('aria-label'),e.getAttribute('title'),e.textContent].filter(Boolean).join(' ').toLowerCase();if(words.some(w=>t.includes(w))){e.click();return 'semantic';}}return 'missing';})()
            """.formatted(jsArray(selectors), jsArray(words));
    }

    private static String jsArray(List<String> values) {
        return "[" + values.stream().map(DomBridge::jsString).reduce((a,b)->a+","+b).orElse("") + "]";
    }

    private static String jsString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "\"";
    }
}

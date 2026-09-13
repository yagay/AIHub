package com.yagay.aihub.web;

import com.yagay.aihub.model.ProviderSpec;

import java.util.List;
import java.util.Locale;

/** Shared DOM engine used by every generic web provider. */
public final class DomBridge {
    private DomBridge() {}

    public static String send(ProviderSpec spec, String text) {
        return format("""
            (()=>{
              const value=%s,inputSelectors=%s,sendSelectors=%s;
              const visible=e=>!!e&&!e.disabled&&!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
              const pick=selectors=>{for(const s of selectors){try{const e=document.querySelector(s);if(visible(e))return e;}catch(_){}}return null;};
              const score=e=>{if(!visible(e))return-1;const r=e.getBoundingClientRect();let n=0;const tag=(e.tagName||'').toLowerCase();if(tag==='textarea')n+=1000;if(e.isContentEditable)n+=900;if(e.getAttribute('role')==='textbox')n+=800;if(r.top>innerHeight*.45)n+=500;return n;};
              const inputs=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(visible).sort((a,b)=>score(b)-score(a));
              const input=pick(inputSelectors)||inputs[0];if(!input)return JSON.stringify({ok:false,stage:'input'});
              input.focus();
              if('value' in input){let p=input,setter=null;while(p&&!setter){const d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)setter=d.set;p=Object.getPrototypeOf(p);}if(setter)setter.call(input,value);else input.value=value;}else input.textContent=value;
              input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));input.dispatchEvent(new Event('change',{bubbles:true}));
              const semantic=[...document.querySelectorAll('button,[role="button"]')].filter(visible).find(e=>{const t=[e.getAttribute('aria-label'),e.getAttribute('title'),e.textContent].filter(Boolean).join(' ').toLowerCase();return ['send','submit','发送','提交','ask'].some(w=>t.includes(w));});
              const button=pick(sendSelectors)||semantic;if(button){button.click();return JSON.stringify({ok:true,method:'button'});}
              input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));input.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));return JSON.stringify({ok:true,method:'enter'});
            })()
            """, jsString(text), jsArray(spec.inputSelectors()), jsArray(spec.sendSelectors()));
    }

    public static String newChat(ProviderSpec spec) {
        return click(spec.newChatSelectors(), List.of("new chat", "new conversation", "新对话", "新聊天"));
    }

    public static String stop(ProviderSpec spec) {
        return click(spec.stopSelectors(), List.of("stop generating", "stop", "停止生成", "停止"));
    }

    public static String attachment(ProviderSpec spec) {
        return format("""
            (()=>{
              const selectors=%s;
              const visible=e=>!!e&&!e.disabled&&!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
              const usable=e=>!!e&&!e.disabled&&(visible(e)||((e.tagName||'').toLowerCase()==='input'&&e.type==='file'));
              for(const s of selectors){try{const e=document.querySelector(s);if(usable(e)){e.click();return 'clicked';}}catch(_){}}
              const file=document.querySelector('input[type="file"]');if(file){file.click();return 'file';}
              const words=['attach','upload','file','photo','image','add file','添加','附件','上传','图片'];
              for(const e of document.querySelectorAll('button,[role="button"]')){if(!visible(e))continue;const t=[e.getAttribute('aria-label'),e.getAttribute('title'),e.textContent].filter(Boolean).join(' ').toLowerCase();if(words.some(w=>t.includes(w))){e.click();return 'semantic';}}
              return 'missing';
            })()
            """, jsArray(spec.attachmentSelectors()));
    }

    /** Returns a small normalized JSON conversation snapshot for the native APP surface. */
    public static String conversation(ProviderSpec spec) {
        return format("""
            (()=>{
              const userSelectors=%s,assistantSelectors=%s;
              const rows=[],seen=new Set();
              const add=(selectors,role)=>{for(const s of selectors){let list=[];try{list=document.querySelectorAll(s);}catch(_){continue;}for(const e of list){if(seen.has(e))continue;const text=(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim();if(!text)continue;seen.add(e);rows.push({el:e,role,text});}}};
              add(userSelectors,'user');add(assistantSelectors,'assistant');
              rows.sort((a,b)=>a.el===b.el?0:((a.el.compareDocumentPosition(b.el)&Node.DOCUMENT_POSITION_FOLLOWING)?-1:1));
              const out=[];for(const row of rows){const prev=out[out.length-1];if(prev&&prev.role===row.role&&prev.text===row.text)continue;out.push({role:row.role,text:row.text});}
              return JSON.stringify(out.slice(-100));
            })()
            """, jsArray(spec.userMessageSelectors()), jsArray(spec.assistantMessageSelectors()));
    }

    private static String click(List<String> selectors, List<String> words) {
        return format("""
            (()=>{const selectors=%s,words=%s;const visible=e=>!!e&&!e.disabled&&!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);for(const s of selectors){try{const e=document.querySelector(s);if(visible(e)){e.click();return 'selector';}}catch(_){}}for(const e of document.querySelectorAll('button,[role="button"],a')){if(!visible(e))continue;const t=[e.getAttribute('aria-label'),e.getAttribute('title'),e.textContent].filter(Boolean).join(' ').toLowerCase();if(words.some(w=>t.includes(w))){e.click();return 'semantic';}}return 'missing';})()
            """, jsArray(selectors), jsArray(words));
    }

    private static String jsArray(List<String> values) {
        return "[" + values.stream().map(DomBridge::jsString).reduce((a,b)->a+","+b).orElse("") + "]";
    }

    private static String jsString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "\"";
    }

    private static String format(String template, Object... args) {
        return String.format(Locale.ROOT, template, args);
    }
}

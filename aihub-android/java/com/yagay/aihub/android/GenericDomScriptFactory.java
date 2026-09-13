package com.yagay.aihub.android;

import com.yagay.aihub.core.ProviderConfig;
import java.util.List;

/** Generic provider-independent DOM actions. Provider selectors are only fallback configuration. */
public final class GenericDomScriptFactory {
    private GenericDomScriptFactory() {}

    public static String fillAndSend(String text, ProviderConfig provider) {
        String inputFallbacks = jsArray(provider.inputSelectors());
        String sendFallbacks = jsArray(provider.sendSelectors());
        return """
            (() => {
              const payload=%s,inputFallbacks=%s,sendFallbacks=%s;
              const visible=el=>!!el&&!el.disabled&&!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length);
              const aihubHidden=el=>!!el&&!!el.closest('[data-aihub-app-hidden="1"]');
              const usable=el=>!!el&&!el.disabled&&(visible(el)||aihubHidden(el));
              const score=el=>{if(!usable(el))return-1;const r=el.getBoundingClientRect();let s=Math.max(0,r.width*r.height);const tag=(el.tagName||'').toLowerCase();if(tag==='textarea')s+=1000000;if(el.getAttribute('role')==='textbox')s+=900000;if(el.isContentEditable)s+=800000;if(r.top>innerHeight*.45)s+=300000;if(aihubHidden(el))s+=500000;return s;};
              const first=selectors=>{for(const selector of selectors){try{const el=document.querySelector(selector);if(usable(el))return el;}catch(_){}}return null;};
              const inputs=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(usable).sort((a,b)=>score(b)-score(a));
              const input=inputs[0]||first(inputFallbacks);if(!input)return JSON.stringify({ok:false,stage:'input'});input.focus();
              if('value' in input){const proto=Object.getPrototypeOf(input),d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(input,payload);else input.value=payload;}else input.textContent=payload;
              input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:payload}));input.dispatchEvent(new Event('change',{bubbles:true}));
              const words=['send','submit','发送','提交','ask','go'];const candidates=[...document.querySelectorAll('button,[role="button"]')].filter(usable).filter(el=>{const t=[el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();return words.some(w=>t.includes(w));});
              const send=candidates[0]||first(sendFallbacks);if(send){send.click();return JSON.stringify({ok:true,method:'button'});}input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));input.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));return JSON.stringify({ok:true,method:'enter'});
            })()
            """.formatted(jsString(text), inputFallbacks, sendFallbacks);
    }

    public static String newChat(ProviderConfig provider) {
        return clickAction(provider.newChatSelectors(), List.of("new chat","new conversation","新对话","新聊天"), "new_chat");
    }

    public static String stop(ProviderConfig provider) {
        return clickAction(provider.stopSelectors(), List.of("stop","停止","停止生成"), "stop");
    }

    /** Clicks the website's real attachment control so Chromium owns its native file chooser. */
    public static String openAttachmentChooser() {
        return """
            (()=>{const visible=el=>!!el&&!el.disabled&&!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length);const usable=el=>!!el&&!el.disabled&&(visible(el)||!!el.closest('[data-aihub-app-hidden="1"]'));const direct=[...document.querySelectorAll('input[type="file"]')].find(el=>!el.disabled);if(direct){direct.click();return JSON.stringify({ok:true,method:'file-input'});}const words=['attach','upload','add file','file','附件','上传','添加文件'];for(const el of document.querySelectorAll('button,[role="button"],a')){if(!usable(el))continue;const t=[el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();if(words.some(w=>t.includes(w))){el.click();return JSON.stringify({ok:true,method:'semantic'});}}return JSON.stringify({ok:false,action:'attach'});})()
            """;
    }

    /** Initializes the fallback Android-share file bridge in the isolated world. */
    public static String uploadInit(int fileCount) {
        return "window.__aihubUpload={files:Array.from({length:%d},()=>({name:'',type:'',b64:''}))};'ok'"
                .formatted(fileCount);
    }

    public static String uploadAppend(int index, String name, String mime, String base64Chunk) {
        return """
            (()=>{const u=window.__aihubUpload;if(!u||!u.files[%d])return 'missing';const f=u.files[%d];if(!f.name)f.name=%s;if(!f.type)f.type=%s;f.b64+=%s;return String(f.b64.length);})()
            """.formatted(index, index, jsString(name), jsString(mime), jsString(base64Chunk));
    }

    /** Commits fallback shared files to the page's real input[type=file]. */
    public static String uploadCommit() {
        return """
            (()=>{try{const u=window.__aihubUpload;if(!u)return JSON.stringify({ok:false,stage:'buffer'});const input=[...document.querySelectorAll('input[type="file"]')].find(el=>!el.disabled);if(!input)return JSON.stringify({ok:false,stage:'file-input'});const dt=new DataTransfer();for(const rec of u.files){const bin=atob(rec.b64);const bytes=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);dt.items.add(new File([bytes],rec.name||'upload.bin',{type:rec.type||'application/octet-stream'}));}input.files=dt.files;input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));delete window.__aihubUpload;return JSON.stringify({ok:true,count:dt.files.length});}catch(e){delete window.__aihubUpload;return JSON.stringify({ok:false,stage:'commit',error:String(e)});}})()
            """;
    }

    public static String probe(ProviderConfig provider) {
        return """
            (()=>{const inputFallbacks=%s,sendFallbacks=%s,newChatFallbacks=%s,stopFallbacks=%s;const visible=el=>!!el&&!el.disabled&&!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length);const usable=el=>!!el&&!el.disabled&&(visible(el)||!!el.closest('[data-aihub-app-hidden="1"]'));const any=s=>s.some(x=>{try{return usable(document.querySelector(x));}catch(_){return false;}});const semantic=words=>[...document.querySelectorAll('button,[role="button"],a')].some(el=>{if(!usable(el))return false;const t=[el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();return words.some(w=>t.includes(w));});const inputs=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(usable);const files=[...document.querySelectorAll('input[type="file"]')].filter(el=>!el.disabled);return JSON.stringify({ok:inputs.length>0||any(inputFallbacks),url:location.href,title:document.title,input:inputs.length>0||any(inputFallbacks),send:semantic(['send','submit','发送','提交','ask','go'])||any(sendFallbacks),newChat:semantic(['new chat','new conversation','新对话','新聊天'])||any(newChatFallbacks),stop:semantic(['stop','停止','停止生成'])||any(stopFallbacks),file:files.length>0});})()
            """.formatted(jsArray(provider.inputSelectors()),jsArray(provider.sendSelectors()),jsArray(provider.newChatSelectors()),jsArray(provider.stopSelectors()));
    }

    /**
     * Turns a normal AI website into an app-like surface.
     *
     * The script only hides website chrome after a chat composer is detected, so login/verification
     * pages remain untouched. Hidden elements are tagged instead of removed, allowing the generic
     * command bridge above to keep operating the official website controls.
     */
    public static String setAppMode(boolean enabled, ProviderConfig provider) {
        return """
            (()=>{
              const enabled=%s,inputFallbacks=%s,STYLE_ID='aihub-app-mode-style',ATTR='data-aihub-app-hidden';
              const clear=()=>{document.querySelectorAll('['+ATTR+']').forEach(el=>el.removeAttribute(ATTR));};
              const stop=()=>{if(window.__aihubAppModeObserver){window.__aihubAppModeObserver.disconnect();delete window.__aihubAppModeObserver;}if(window.__aihubAppModeTimer){clearTimeout(window.__aihubAppModeTimer);delete window.__aihubAppModeTimer;}clear();const old=document.getElementById(STYLE_ID);if(old)old.remove();document.documentElement.removeAttribute('data-aihub-app-mode');};
              if(!enabled){stop();return JSON.stringify({ok:true,enabled:false});}
              let style=document.getElementById(STYLE_ID);if(!style){style=document.createElement('style');style.id=STYLE_ID;style.textContent='[data-aihub-app-hidden="1"]{display:none!important}html[data-aihub-app-mode="1"],html[data-aihub-app-mode="1"] body{overscroll-behavior-y:contain!important}';(document.head||document.documentElement).appendChild(style);}document.documentElement.setAttribute('data-aihub-app-mode','1');
              const visible=el=>!!el&&!el.disabled&&!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length);
              const first=selectors=>{for(const selector of selectors){try{const el=document.querySelector(selector);if(visible(el))return el;}catch(_){}}return null;};
              const score=el=>{if(!visible(el))return-1;const r=el.getBoundingClientRect();let s=Math.max(0,r.width*r.height);const tag=(el.tagName||'').toLowerCase();if(tag==='textarea')s+=1000000;if(el.getAttribute('role')==='textbox')s+=900000;if(el.isContentEditable)s+=800000;if(r.top>innerHeight*.45)s+=300000;return s;};
              const findInput=()=>{const inputs=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(visible).sort((a,b)=>score(b)-score(a));return inputs[0]||first(inputFallbacks);};
              const mark=el=>{if(el&&el!==document.body&&el!==document.documentElement)el.setAttribute(ATTR,'1');};
              const apply=()=>{clear();const input=findInput();if(!input)return false;let composer=input;for(let i=0;i<8&&composer&&composer.parentElement;i++){const parent=composer.parentElement;if(parent===document.body)break;const r=parent.getBoundingClientRect();if(r.width>=innerWidth*.45&&r.height>0&&r.height<=innerHeight*.38&&r.bottom>=innerHeight*.70)composer=parent;else if(composer!==input)break;else composer=parent;}mark(composer);
                for(const el of document.querySelectorAll('aside,nav,[role="navigation"],header')){if(!visible(el)||el.contains(input)||input.contains(el))continue;const r=el.getBoundingClientRect();const tag=(el.tagName||'').toLowerCase();const side=(tag==='aside'||tag==='nav'||el.getAttribute('role')==='navigation')&&r.width>0&&r.width<=innerWidth*.58&&r.height>=innerHeight*.28;const top=(tag==='header'||r.top<=12)&&r.height>0&&r.height<=Math.min(180,innerHeight*.24)&&r.width>=innerWidth*.55;if(side||top)mark(el);}return true;};
              if(window.__aihubAppModeObserver)window.__aihubAppModeObserver.disconnect();
              const schedule=()=>{if(window.__aihubAppModeTimer)clearTimeout(window.__aihubAppModeTimer);window.__aihubAppModeTimer=setTimeout(()=>{delete window.__aihubAppModeTimer;apply();},120);};
              window.__aihubAppModeObserver=new MutationObserver(schedule);window.__aihubAppModeObserver.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['class','style','hidden','aria-hidden']});
              const active=apply();return JSON.stringify({ok:true,enabled:true,active});
            })()
            """.formatted(enabled ? "true" : "false", jsArray(provider.inputSelectors()));
    }

    private static String clickAction(List<String> fallbacks, List<String> words, String action) {
        return """
            (()=>{const fallbacks=%s,words=%s;const visible=el=>!!el&&!el.disabled&&!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length);const usable=el=>!!el&&!el.disabled&&(visible(el)||!!el.closest('[data-aihub-app-hidden="1"]'));for(const el of document.querySelectorAll('button,[role="button"],a')){if(!usable(el))continue;const t=[el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();if(words.some(w=>t.includes(w))){el.click();return JSON.stringify({ok:true,method:'semantic',action:%s});}}for(const selector of fallbacks){try{const el=document.querySelector(selector);if(usable(el)){el.click();return JSON.stringify({ok:true,method:'selector',action:%s});}}catch(_){}}return JSON.stringify({ok:false,action:%s});})()
            """.formatted(jsArray(fallbacks),jsArray(words),jsString(action),jsString(action),jsString(action));
    }

    private static String jsArray(List<String> values) {
        return "[" + values.stream().map(GenericDomScriptFactory::jsString).reduce((a,b)->a+","+b).orElse("") + "]";
    }

    private static String jsString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\u2028","\\u2028").replace("\u2029","\\u2029") + "\"";
    }
}

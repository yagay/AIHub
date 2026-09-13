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
              const score=el=>{if(!visible(el))return-1;const r=el.getBoundingClientRect();let s=Math.max(0,r.width*r.height);const tag=(el.tagName||'').toLowerCase();if(tag==='textarea')s+=1000000;if(el.getAttribute('role')==='textbox')s+=900000;if(el.isContentEditable)s+=800000;if(r.top>innerHeight*.45)s+=300000;return s;};
              const first=selectors=>{for(const selector of selectors){try{const el=document.querySelector(selector);if(visible(el))return el;}catch(_){}}return null;};
              const inputs=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(visible).sort((a,b)=>score(b)-score(a));
              const input=inputs[0]||first(inputFallbacks);if(!input)return JSON.stringify({ok:false,stage:'input'});input.focus();
              if('value' in input){const proto=Object.getPrototypeOf(input),d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(input,payload);else input.value=payload;}else input.textContent=payload;
              input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:payload}));input.dispatchEvent(new Event('change',{bubbles:true}));
              const words=['send','submit','发送','提交','ask','go'];const candidates=[...document.querySelectorAll('button,[role="button"]')].filter(visible).filter(el=>{const t=[el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();return words.some(w=>t.includes(w));});
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
            (()=>{const visible=el=>!!el&&!el.disabled&&!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length);const direct=[...document.querySelectorAll('input[type="file"]')].find(el=>!el.disabled);if(direct){direct.click();return JSON.stringify({ok:true,method:'file-input'});}const words=['attach','upload','add file','file','附件','上传','添加文件'];for(const el of document.querySelectorAll('button,[role="button"],a')){if(!visible(el))continue;const t=[el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();if(words.some(w=>t.includes(w))){el.click();return JSON.stringify({ok:true,method:'semantic'});}}return JSON.stringify({ok:false,action:'attach'});})()
            """;
    }

    /** Fallback used for URI-based Android shares; normal in-app attachment uses Chromium chooser. */
    public static String uploadInit(int fileCount) {
        return "window.__aihubUpload={files:Array.from({length:%d},()=>({name:'',type:'',b64:''}))};'ok'".formatted(fileCount);
    }

    public static String uploadAppend(int index, String name, String mime, String base64Chunk) {
        return """
            (()=>{const u=window.__aihubUpload;if(!u||!u.files[%d])return 'missing';const f=u.files[%d];if(!f.name)f.name=%s;if(!f.type)f.type=%s;f.b64+=%s;return String(f.b64.length);})()
            """.formatted(index, index, jsString(name), jsString(mime), jsString(base64Chunk));
    }

    public static String uploadCommit() {
        return """
            (()=>{try{const u=window.__aihubUpload;if(!u)return JSON.stringify({ok:false,stage:'buffer'});const input=[...document.querySelectorAll('input[type="file"]')].find(el=>!el.disabled);if(!input)return JSON.stringify({ok:false,stage:'file-input'});const dt=new DataTransfer();for(const rec of u.files){const bin=atob(rec.b64);const bytes=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);dt.items.add(new File([bytes],rec.name||'upload.bin',{type:rec.type||'application/octet-stream'}));}input.files=dt.files;input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));delete window.__aihubUpload;return JSON.stringify({ok:true,count:dt.files.length});}catch(e){delete window.__aihubUpload;return JSON.stringify({ok:false,stage:'commit',error:String(e)});}})()
            """;
    }

    public static String probe(ProviderConfig provider) {
        return """
            (()=>{const inputFallbacks=%s,sendFallbacks=%s,newChatFallbacks=%s,stopFallbacks=%s;const visible=el=>!!el&&!el.disabled&&!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length);const any=s=>s.some(x=>{try{return visible(document.querySelector(x));}catch(_){return false;}});const semantic=words=>[...document.querySelectorAll('button,[role="button"],a')].some(el=>{if(!visible(el))return false;const t=[el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();return words.some(w=>t.includes(w));});const inputs=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')].filter(visible);const files=[...document.querySelectorAll('input[type="file"]')].filter(el=>!el.disabled);return JSON.stringify({ok:inputs.length>0||any(inputFallbacks),url:location.href,title:document.title,input:inputs.length>0||any(inputFallbacks),send:semantic(['send','submit','发送','提交','ask','go'])||any(sendFallbacks),newChat:semantic(['new chat','new conversation','新对话','新聊天'])||any(newChatFallbacks),stop:semantic(['stop','停止','停止生成'])||any(stopFallbacks),file:files.length>0});})()
            """.formatted(jsArray(provider.inputSelectors()),jsArray(provider.sendSelectors()),jsArray(provider.newChatSelectors()),jsArray(provider.stopSelectors()));
    }

    private static String clickAction(List<String> fallbacks, List<String> words, String action) {
        return """
            (()=>{const fallbacks=%s,words=%s;const visible=el=>!!el&&!el.disabled&&!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length);for(const el of document.querySelectorAll('button,[role="button"],a')){if(!visible(el))continue;const t=[el.getAttribute('aria-label'),el.getAttribute('title'),el.textContent].filter(Boolean).join(' ').toLowerCase();if(words.some(w=>t.includes(w))){el.click();return JSON.stringify({ok:true,method:'semantic',action:%s});}}for(const selector of fallbacks){try{const el=document.querySelector(selector);if(visible(el)){el.click();return JSON.stringify({ok:true,method:'selector',action:%s});}}catch(_){}}return JSON.stringify({ok:false,action:%s});})()
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

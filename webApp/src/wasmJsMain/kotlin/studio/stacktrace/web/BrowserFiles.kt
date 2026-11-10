package studio.stacktrace.web

import kotlin.js.*
import kotlinx.coroutines.await
import kotlinx.serialization.json.*

@JsFun("() => window.location.origin")
external fun browserOrigin(): String

@JsFun("""() => new Promise(resolve => {
 const input = document.createElement('input'); input.type='file'; input.accept='.txt,text/plain';
 input.oncancel=()=>resolve('');
 input.onchange=async()=>{const f=input.files[0]; if(!f){resolve('');return;}
   if(f.size>1048576){resolve(JSON.stringify({error:'Log exceeds 1 MiB'}));return;}
   try{resolve(JSON.stringify({text:await f.text()}));}catch(e){resolve(JSON.stringify({error:'Cannot read file'}));}
 }; input.click();
})""")
private external fun pickTextJs(): Promise<JsString>

@JsFun("""(url,token,apk) => new Promise(resolve => {
 const input=document.createElement('input'); input.type='file'; input.accept=apk?'.apk':'.txt';
 input.oncancel=()=>resolve('');
 input.onchange=async()=>{const f=input.files[0]; if(!f){resolve('');return;}
   try { const data=new FormData(); data.append('file',f);
     const headers=token?{Authorization:'Bearer '+token}:{};
     const response=await fetch(url,{method:'POST',headers,body:data});
     resolve(JSON.stringify({status:response.status,body:await response.text()}));
   }catch(e){resolve(JSON.stringify({error:'Upload failed. Check the connection.'}));}
 }; input.click();
})""")
private external fun uploadJs(url: String,token: String,apk: Boolean): Promise<JsString>

@JsFun("""text => new Promise(resolve => {
 if(!navigator.clipboard){resolve('Clipboard requires HTTPS or localhost');return;}
 navigator.clipboard.writeText(text).then(()=>resolve(''),()=>resolve('Clipboard permission denied'));
})""")
private external fun copyJs(text: String): Promise<JsString>

@JsFun("""text => {
 const url=URL.createObjectURL(new Blob([text],{type:'text/plain;charset=utf-8'}));
 const a=document.createElement('a'); a.href=url; a.download='translated.txt';
 document.body.appendChild(a); a.click(); a.remove(); setTimeout(()=>URL.revokeObjectURL(url),1000);
}""")
external fun downloadText(text: String)

@JsFun("message => window.confirm(message)")
external fun confirmDelete(message: String): Boolean

suspend fun loadText(): String? {
    val result=pickTextJs().await<JsString>().toString()
    if(result.isEmpty()) return null
    val data=Json.parseToJsonElement(result).jsonObject
    data["error"]?.let { error(it.jsonPrimitive.content) }
    return data.getValue("text").jsonPrimitive.content
}
suspend fun uploadArtifact(buildId: String,token: String,apk: Boolean): Boolean {
    val result=uploadJs("${browserOrigin()}/api/v1/builds/$buildId/${if(apk) "apk" else "mapping"}",token,apk).await<JsString>().toString()
    if(result.isEmpty()) return false
    val data=Json.parseToJsonElement(result).jsonObject
    data["error"]?.let { error(it.jsonPrimitive.content) }
    if(data.getValue("status").jsonPrimitive.int !in 200..299) {
        val message=runCatching { Json.parseToJsonElement(data.getValue("body").jsonPrimitive.content).jsonObject.getValue("message").jsonPrimitive.content }.getOrDefault("Upload rejected")
        error(message)
    }
    return true
}
suspend fun copyText(text: String) {
    val error=copyJs(text).await<JsString>().toString()
    check(error.isEmpty()) { error }
}

// Uses Node's built-in CDP client; no browser automation package is required.
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir, homedir } from 'node:os';
import { resolve, join } from 'node:path';
import net from 'node:net';
import { createHash } from 'node:crypto';
const directory=await mkdtemp(join(tmpdir(),'studio-browser-'));
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const freePort=()=>new Promise(r=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>r(p));});});
const port=await freePort();
const origin=`http://127.0.0.1:${port}`;
let server,chrome,ws;
try {
  server=spawn(resolve('server/build/install/server/bin/server'),[],{env:{...process.env,JAVA_HOME:process.env.JAVA_HOME||join(homedir(),'.jdks/jbr-21.0.11'),STACKTRACE_DATA_DIR:join(directory,'data'),STACKTRACE_WEB_DIR:resolve('webApp/build/dist/wasmJs/productionExecutable'),STACKTRACE_API_TOKEN:'browser-test-token',PORT:String(port)},stdio:['ignore','pipe','pipe']});
  let logs='';server.stdout.on('data',d=>logs+=d);server.stderr.on('data',d=>logs+=d);
  for(let i=0;;i++){try{if((await fetch(origin+'/health')).ok)break;}catch{}if(i===100)throw Error('Server startup failed: '+logs);await delay(100);}
  chrome=spawn(process.env.CHROMIUM||'chromium',['--headless','--no-sandbox','--disable-dev-shm-usage','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${join(directory,'chrome')}`,'about:blank'],{stdio:['ignore','ignore','pipe']});
  let chromeLogs='',chromeError;chrome.stderr.on('data',d=>chromeLogs+=d);chrome.on('error',error=>chromeError=error);
  let debugPort;
  for(let i=0;;i++){try{debugPort=Number((await readFile(join(directory,'chrome/DevToolsActivePort'),'utf8')).split('\n')[0]);break;}catch{}if(chromeError||chrome.exitCode!==null||chrome.signalCode!==null||i===300)throw Error('Chromium startup failed: '+(chromeError?.message||'exit='+chrome.exitCode+' signal='+chrome.signalCode)+'\n'+chromeLogs);await delay(100);}
  const tabs=await(await fetch(`http://127.0.0.1:${debugPort}/json`)).json();
  ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl);
  await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
  let next=0;const pending=new Map();const errors=[];const responses=[];const fileChoosers=[];const completedRequests=new Set();
  ws.onmessage=event=>{const message=JSON.parse(event.data);if(message.id){const p=pending.get(message.id);pending.delete(message.id);message.error?p.reject(Error(JSON.stringify(message.error))):p.resolve(message.result);}if(message.method==='Network.loadingFinished')completedRequests.add(message.params.requestId);if(message.method==='Page.fileChooserOpened')fileChoosers.push(message.params);if(message.method==='Network.responseReceived')responses.push(message.params);if(message.method==='Runtime.exceptionThrown')errors.push(message.params.exceptionDetails);};
  const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++next;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
  const responseBodyFor=async requestId=>{for(let i=0;!completedRequests.has(requestId);i++){if(i===300)throw Error('Response download did not finish: '+requestId);await delay(100);}return JSON.parse((await cdp('Network.getResponseBody',{requestId})).body);};
  await cdp('Runtime.enable');await cdp('Page.enable');await cdp('Network.enable');
  await cdp('Emulation.setDeviceMetricsOverride',{width:1200,height:900,deviceScaleFactor:1,mobile:false});
  await cdp('Page.navigate',{url:origin});
  await delay(12000);
  const dom=await cdp('Runtime.evaluate',{expression:'document.body.innerHTML',returnByValue:true});
  if(!dom.result.value.includes('<canvas')) throw Error('Compose canvas did not mount');
  const screenshot=await cdp('Page.captureScreenshot',{format:'png'});
  const path=resolve('build/browser-initial.png');await writeFile(path,Buffer.from(screenshot.data,'base64'));console.log('Screenshot:',path);
  const click=async(x,y)=>{await cdp('Input.dispatchMouseEvent',{type:'mouseMoved',x,y});await cdp('Input.dispatchMouseEvent',{type:'mousePressed',x,y,button:'left',clickCount:1});await cdp('Input.dispatchMouseEvent',{type:'mouseReleased',x,y,button:'left',clickCount:1});};
  const screenshotTo=async name=>{const shot=await cdp('Page.captureScreenshot',{format:'png'});await writeFile(resolve('build/'+name+'.png'),Buffer.from(shot.data,'base64'));};
  const type=async text=>{for(const character of text){const keyCode=character==='-'?189:character==='.'?190:character.toUpperCase().charCodeAt(0);await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:character,text:character,unmodifiedText:character,windowsVirtualKeyCode:keyCode});await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:character,windowsVirtualKeyCode:keyCode});}};
  const fill=async(x,y,text)=>{await click(x,y);await delay(150);await type(text);};
  const waitResponse=async(path,status)=>{for(let i=0;;i++){const found=responses.find(r=>r.response.url.endsWith(path)&&r.response.status===status);if(found)return found;if(i===150)throw Error('Missing response '+status+' '+path);await delay(100);}};
  await fill(250,216,'browser-test-token');await click(75,284);
  for(let i=0;;i++){if(responses.some(r=>r.response.url.endsWith('/api/v1/projects')&&r.response.status===200))break;if(i===100)throw Error('UI sign-in did not fetch projects');await delay(100);}
  await delay(500);await screenshotTo('browser-connected');
  await click(95,252);await delay(300);await screenshotTo('browser-project-dialog');
  await fill(600,406,'browserapp');await fill(600,478,'com.example.browser');await click(710,555);
  await waitResponse('/api/v1/projects',201);await delay(600);await screenshotTo('browser-project-created');
  await click(655,249);await delay(300);await screenshotTo('browser-build-dialog');
  await fill(600,382,'1.0');await fill(600,454,'72');await click(710,580);await delay(800);await screenshotTo('browser-build-created');
  await cdp('Page.setInterceptFileChooserDialog',{enabled:true});
  const chooseFile=async(x,y,path)=>{const count=fileChoosers.length;await click(x,y);for(let i=0;fileChoosers.length===count;i++){if(i===100)throw Error('File chooser did not open');await delay(100);}const chooser=fileChoosers.at(-1);await cdp('DOM.setFileInputFiles',{files:[resolve(path)],backendNodeId:chooser.backendNodeId});};
  await chooseFile(110,361,'examples/mapping.txt');
  for(let i=0;;i++){if(responses.some(r=>r.response.url.endsWith('/mapping')&&r.response.status===200))break;if(i===100)throw Error('Mapping upload failed');await delay(100);}
  await delay(700);await screenshotTo('browser-mapping-uploaded');
  await chooseFile(75,825,'examples/crash.txt');await delay(500);await screenshotTo('browser-trace-loaded');
  await click(190,785);await delay(800);await screenshotTo('browser-retraced');
  const retraceResponse=await waitResponse('/api/v1/retrace/batch',200);
  const responseBody=await responseBodyFor(retraceResponse.requestId);
  const expected='com.example.Crash.explode(Crash.kt:42)';
  if(!responseBody.results[0].translated.includes(expected))throw Error('UI retrace did not restore source/line');
  if(responseBody.results[0].original!==await readFile('examples/crash.txt','utf8'))throw Error('Original trace was changed');
  await cdp('Browser.grantPermissions',{origin,permissions:['clipboardReadWrite','clipboardSanitizedWrite']});
  await click(285,825);await delay(500);
  const clipboard=await cdp('Runtime.evaluate',{expression:'navigator.clipboard.readText()',awaitPromise:true,returnByValue:true});
  if(!clipboard.result.value?.includes(expected))throw Error('Copy did not write translated output');
  const downloads=join(directory,'downloads');await mkdir(downloads);
  await cdp('Browser.setDownloadBehavior',{behavior:'allow',downloadPath:downloads,eventsEnabled:true});
  await click(400,785);
  let exported;
  for(let i=0;;i++){try{exported=await readFile(join(downloads,'translated.txt'),'utf8');break;}catch{}if(i===100)throw Error('TXT download did not complete');await delay(100);}
  if(exported!==clipboard.result.value)throw Error('Export differs from copied translation');
  await writeFile(resolve('build/browser-export.txt'),exported);
  const batchPath=join(directory,'batch.txt');const original=await readFile('examples/crash.txt','utf8');await writeFile(batchPath,original+'\n---\n'+original);
  await click(48,425);await chooseFile(75,785,batchPath);await delay(300);
  const previousBatches=responses.filter(r=>r.response.url.endsWith('/api/v1/retrace/batch')).length;
  await click(190,785);
  let batchResponse;
  for(let i=0;;i++){const list=responses.filter(r=>r.response.url.endsWith('/api/v1/retrace/batch'));if(list.length>previousBatches){batchResponse=list.at(-1);break;}if(i===100)throw Error('Batch UI did not submit');await delay(100);}
  await delay(500);
  const batchBody=await responseBodyFor(batchResponse.requestId);
  if(batchBody.results.length!==2||!batchBody.results.every(r=>r.success&&r.translated.includes(expected)))throw Error('Batch UI results were incorrect');
  await screenshotTo('browser-batch');
  const batchesBeforeShortcut=responses.filter(r=>r.response.url.endsWith('/api/v1/retrace/batch')).length;
  await click(200,600);
  await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:'Control',code:'ControlLeft',windowsVirtualKeyCode:17,modifiers:2});
  await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,modifiers:2});
  await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,modifiers:2});
  await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:'Control',code:'ControlLeft',windowsVirtualKeyCode:17});
  let shortcutResponse;
  for(let i=0;;i++){const list=responses.filter(r=>r.response.url.endsWith('/api/v1/retrace/batch'));if(list.length>batchesBeforeShortcut){shortcutResponse=list.at(-1);break;}if(i===100)throw Error('Ctrl+Enter did not submit');await delay(100);}
  await delay(500);
  const shortcutBody=await responseBodyFor(shortcutResponse.requestId);
  if(JSON.stringify(shortcutBody.results.map(r=>r.original))!==JSON.stringify(batchBody.results.map(r=>r.original)))throw Error('Shortcut modified original traces');
  if(!shortcutBody.results.every(r=>r.success))throw Error('Shortcut retrace failed');
  await click(1030,48);await delay(300);await screenshotTo('browser-light-theme');
  await click(1030,48);await delay(300);
  await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:false});
  await delay(700);await screenshotTo('browser-mobile-top');
  await cdp('Input.dispatchMouseEvent',{type:'mouseWheel',x:350,y:700,deltaX:0,deltaY:1600});
  await delay(500);await screenshotTo('browser-mobile-bottom');
  console.log('Browser E2E passed: project/build/upload/retrace, original preservation, copy/download, batch, Ctrl+Enter and narrow-viewport rendering.');
  if(errors.length)throw Error('Browser reported runtime exceptions: '+JSON.stringify(errors));
  await writeFile(resolve('build/browser-verification.json'),JSON.stringify({passed:true,checkedAt:new Date().toISOString(),checks:['UI sign-in','UI project/build creation','mapping upload','TXT input','official R8 retrace','original preservation','clipboard','TXT download','batch translation','Ctrl+Enter preserves input','theme rendering','390px viewport rendering'],batchResults:batchBody.results.length,clientBundleSha256:createHash('sha256').update(await readFile(resolve('webApp/build/dist/wasmJs/productionExecutable/stacktrace-web.js'))).digest('hex')},null,2));
} finally {
  if(ws)ws.close();
  for(const child of [chrome,server])if(child&&!child.killed)child.kill('SIGTERM');
  await delay(1000);
  await rm(directory,{recursive:true,force:true,maxRetries:5,retryDelay:200});
}

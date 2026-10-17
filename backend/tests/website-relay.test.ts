import assert from 'node:assert/strict';
import { test } from 'node:test';
import express from 'express';
import { randomUUID } from 'node:crypto';
import { websiteRelay } from '../src/protocol/WebsiteRelay.js';
import { parseLogicalMessage } from '../src/protocol/LogicalMessage.js';

test('Explicit website relay: authentication, two peers, shared envelope, duplicate suppression', async()=>{
  const app=express();app.use(express.json());app.use('/api/v1/swar',websiteRelay());
  const server=app.listen(0,'127.0.0.1');await new Promise<void>(resolve=>server.once('listening',resolve));
  try {
    const address=server.address();assert.ok(address&&typeof address!=='string');const base=`http://127.0.0.1:${address.port}`;
    const room=await (await fetch(base+'/api/v1/swar/rooms',{method:'POST'})).json() as {roomId:string;token:string;path:string};
    const a=randomUUID(),b=randomUUID();const headers={'Content-Type':'application/json',Authorization:`Bearer ${room.token}`};
    assert.equal((await fetch(base+room.path+'/messages')).status,403);
    for(const id of [a,b])assert.equal((await fetch(base+room.path+'/join',{method:'POST',headers,body:JSON.stringify({clientId:id})})).status,200);
    assert.equal((await fetch(base+room.path+'/join',{method:'POST',headers,body:JSON.stringify({clientId:randomUUID()})})).status,409);
    const message=parseLogicalMessage({type:'SWAR_MESSAGE',sessionId:room.roomId,sequence:42,language:'hinglish',encoding:'text_v1',payload:'bhai मदद चाहिए',timestamp:0});
    const send=()=>fetch(base+room.path+'/messages',{method:'POST',headers,body:JSON.stringify({clientId:a,message})});
    assert.equal((await send()).status,200);assert.equal((await (await send()).json() as {duplicate:boolean}).duplicate,true);
    const received=await (await fetch(base+room.path+`/messages?clientId=${b}&after=0`,{headers})).json() as {messages:unknown[];cursor:number};
    assert.deepEqual(received.messages,[message]);
    const next=await (await fetch(base+room.path+`/messages?clientId=${b}&after=${received.cursor}`,{headers})).json() as {messages:unknown[]};assert.deepEqual(next.messages,[]);
    assert.throws(()=>parseLogicalMessage({...message,encoding:'audio' as any}));assert.throws(()=>parseLogicalMessage({...message,sequence:0}));assert.throws(()=>parseLogicalMessage({...message,payload:'x'.repeat(8193)}));
  } finally {await new Promise<void>((resolve,reject)=>server.close(e=>e?reject(e):resolve()));}
});

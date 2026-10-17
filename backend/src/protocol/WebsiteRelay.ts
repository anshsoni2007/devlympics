import { Router } from 'express';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { parseLogicalMessage, SwarMessage } from './LogicalMessage.js';

/** Explicit network mode only. In-memory two-client relay; never used by acoustic Android transport. */
export function websiteRelay(): Router {
  const router=Router();
  type Entry={cursor:number;sender:string;message:SwarMessage};
  type Room={token:string;clients:Set<string>;entries:Entry[];next:number;seen:Map<string,number>;touched:number};
  const rooms=new Map<string,Room>();
  router.use((_req,_res,next)=>{for(const [id,room] of rooms)if(Date.now()-room.touched>300000)rooms.delete(id);next();});
  router.post('/rooms',(_req,res)=>{
    if(rooms.size>=64){res.status(429).json({error:'Relay capacity reached'});return;}
    const id=randomUUID(),token=randomUUID();rooms.set(id,{token,clients:new Set(),entries:[],next:0,seen:new Map(),touched:Date.now()});
    res.json({roomId:id,token,path:`/api/v1/swar/rooms/${id}`,expiresAfterIdleMs:300000});
  });
  router.use('/rooms/:id',(req,res,next)=>{
    const room=rooms.get(req.params.id);const token=req.headers.authorization?.replace(/^Bearer /,'') ?? '';
    if(!room||!/^[-a-f0-9]{36}$/i.test(token)||token.length!==room.token.length||!timingSafeEqual(Buffer.from(token),Buffer.from(room.token))){res.status(403).json({error:'Invalid room or token'});return;}
    room.touched=Date.now();res.locals.room=room;res.locals.roomId=req.params.id;next();
  });
  router.post('/rooms/:id/join',(req,res)=>{
    const room=res.locals.room as Room;const client=String(req.body.clientId??'');
    if(!/^[0-9a-f-]{36}$/i.test(client)){res.status(400).json({error:'Invalid client ID'});return;}
    if(!room.clients.has(client)&&room.clients.size>=2){res.status(409).json({error:'Room already has two clients'});return;}
    room.clients.add(client);res.json({clients:room.clients.size,sessionId:res.locals.roomId});
  });
  router.get('/rooms/:id/messages',(req,res)=>{
    const room=res.locals.room as Room,client=String(req.query.clientId??''),after=Number(req.query.after??0);
    if(!room.clients.has(client)||!Number.isSafeInteger(after)||after<0){res.status(400).json({error:'Join room first / invalid cursor'});return;}
    if(room.entries.length&&after<room.entries[0].cursor-1){res.status(409).json({error:'Relay history expired; start a new room'});return;}
    res.json({clients:room.clients.size,cursor:room.next,messages:room.entries.filter(e=>e.cursor>after&&e.sender!==client).map(e=>e.message)});
  });
  router.post('/rooms/:id/messages',(req,res)=>{
    const room=res.locals.room as Room,client=String(req.body.clientId??'');
    try{
      const message=parseLogicalMessage(req.body.message);
      if(!room.clients.has(client)||room.clients.size!==2||message.sessionId!==res.locals.roomId)throw new Error('Join both clients first / wrong session');
      const key=`${client}:${message.sequence}`;const existing=room.seen.get(key);
      if(existing!==undefined){res.json({state:'QUEUED',cursor:existing,duplicate:true});return;}
      const cursor=++room.next;room.entries.push({cursor,sender:client,message});room.seen.set(key,cursor);
      if(room.entries.length>128){const old=room.entries.shift()!;room.seen.delete(`${old.sender}:${old.message.sequence}`);}
      res.json({state:'QUEUED',cursor});
    }catch(e){res.status(400).json({error:e instanceof Error?e.message:'Invalid message'});}
  });
  return router;
}

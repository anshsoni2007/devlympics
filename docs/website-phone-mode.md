# Explicit website ↔ phone mode

The existing website/backend prototype remains separate from acoustic phone-to-phone operation. A browser-compatible HTTP relay is mounted at `/api/v1/swar` in Express. Android `WebsiteTransport` uses HTTPS polling and is instantiated only when the user explicitly selects Website in the connection drawer. No acoustic message is sent to this service. TLS certificate validation remains enabled and redirects are refused. Serve the backend behind a trusted HTTPS endpoint for a physical phone; HTTP loopback is used only by the automated relay test.

The shared logical schema is validated in Java `LogicalMessage` and TypeScript `protocol/LogicalMessage.ts`:

```json
{"type":"SWAR_MESSAGE","sessionId":"e67daac2-195f-4567-91d4-94c908ab1954","sequence":42,"language":"hi","encoding":"text_v1","payload":"मदद चाहिए","timestamp":0}
```

The acoustic path encodes the same session/sequence/language/text through unchanged ITP/1. Website transport uses JSON; the semantic message does not depend on FSK or sockets.

Create a room using `POST /api/v1/swar/rooms`. It returns `roomId`, a bearer `token`, and `path`. Join two clients using `POST {path}/join` with `{clientId: "a random UUID"}` and the header `Authorization: Bearer TOKEN`. Construct the Android connection link as `https://YOUR_HOST{path}#TOKEN` and paste it in the existing connection field after selecting Website. The fragment is parsed locally, removed from requests and sent as the authorization header. Do not publish this link; it grants room access.

The browser uses the same endpoints:

```js
const clientId = crypto.randomUUID();
const room = await (await fetch('/api/v1/swar/rooms', {method:'POST'})).json();
const headers = {'Content-Type':'application/json', Authorization:`Bearer ${room.token}`};
await fetch(room.path+'/join', {method:'POST', headers, body:JSON.stringify({clientId})});
// Copy `${location.origin}${room.path}#${room.token}` to the phone connection field.
// Once both clients have joined:
await fetch(room.path+'/messages', {method:'POST', headers, body:JSON.stringify({clientId,
  message:{type:'SWAR_MESSAGE', sessionId:room.roomId, sequence:1,
    language:'en', encoding:'text_v1', payload:'Hello phone', timestamp:Date.now()}})});
let cursor=0;
async function poll() {
  const response=await fetch(`${room.path}/messages?clientId=${clientId}&after=${cursor}`, {headers});
  if(!response.ok) throw new Error(`Relay HTTP ${response.status}`);
  const result=await response.json();
  for(const message of result.messages) console.log(message.payload); // Render using textContent.
  cursor=result.cursor;
}
```

Poll at a bounded interval such as one second; clear intervals on exit. Never inject received text using innerHTML. The relay keeps at most two clients per room, 64 rooms and 128 messages per room in RAM. Rooms expire after five idle minutes. No database or inference service is used. `QUEUED` means accepted by the relay, not decoded/played by the phone. A cursor older than retained history fails explicitly. This is a development relay with bearer access, not an authenticated public production service or a claim of end-to-end encryption. Website frontend integration can call these endpoints without changing acoustic code.

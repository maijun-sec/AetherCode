"""Wait up to 30s for stream events from a query."""
import json, asyncio, time, sys
from collections import Counter

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17889

async def t():
    import websockets
    async with websockets.connect(f'ws://127.0.0.1:{PORT}/ws') as ws:
        await ws.recv()  # welcome
        await ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':'say hi briefly'}}))
        end = time.time() + 30
        events = []
        while time.time() < end:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=2)
                d = json.loads(msg)
                if 'event' in d:
                    et = d['event'].get('type')
                    if et == 'text_delta':
                        sys.stdout.write(d['event'].get('text',''))
                        sys.stdout.flush()
                    elif et:
                        print(f' [{et}]', flush=True)
                    events.append(et)
                elif 'id' in d:
                    print(f" [RPC-OK] result={d.get('result')}", flush=True)
            except asyncio.TimeoutError:
                # no event in 2s — check if the stream has completed
                if events and 'run_end' in events:
                    break
                continue
        print()
        print(f'== total events: {len(events)} ==')
        print(Counter(events))

asyncio.run(t())

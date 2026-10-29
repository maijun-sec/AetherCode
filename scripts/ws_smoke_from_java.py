# Copyright (c) 2026 maijun

# Licensed under the MIT License.
# See the LICENSE file in the project root for the full license text.

import json, asyncio, time, sys

async def t():
    import websockets
    async with websockets.connect('ws://127.0.0.1:17888/ws') as ws:
        await ws.recv()
        await ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':'say hi in 3 words'}}))
        end = time.time() + 12
        while time.time() < end:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=1)
                d = json.loads(msg)
                if 'event' in d:
                    t = d['event'].get('type')
                    if t == 'text_delta':
                        sys.stdout.write(d['event'].get('text',''))
                        sys.stdout.flush()
                    elif t:
                        print(f' [{t}]')
                elif 'id' in d and 'result' in d:
                    print('result ok')
            except asyncio.TimeoutError:
                break
        print()

asyncio.run(t())

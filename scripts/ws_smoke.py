"""Wait up to 30s for stream events from a query."""
# Copyright (c) 2026 maijun
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

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

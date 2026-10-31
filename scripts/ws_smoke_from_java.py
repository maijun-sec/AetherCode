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

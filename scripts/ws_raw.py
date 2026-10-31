"""Raw debug — read all frames, no filtering."""
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

import asyncio, sys, time
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17889
async def t():
    import websockets
    async with websockets.connect(f'ws://127.0.0.1:{PORT}/ws') as ws:
        print(f'[{time.strftime("%H:%M:%S")}] connected', flush=True)
        # Read welcome
        w = await ws.recv()
        print(f'[{time.strftime("%H:%M:%S")}] welcome len={len(w)}', flush=True)
        # Send query
        import json
        await ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':'say hi briefly'}}))
        print(f'[{time.strftime("%H:%M:%S")}] query sent', flush=True)
        end = time.time() + 20
        n = 0
        while time.time() < end:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=1)
                n += 1
                preview = msg[:140].replace('\n', '\\n')
                print(f'[{time.strftime("%H:%M:%S")}] frame #{n} len={len(msg)} preview={preview!r}', flush=True)
                if '"run_end"' in msg and '"stream_event"' in msg:
                    print(f'[{time.strftime("%H:%M:%S")}] run_end seen, exit', flush=True)
                    break
            except asyncio.TimeoutError:
                # check WS state
                if ws.state.name == 'CLOSED':
                    print(f'[{time.strftime("%H:%M:%S")}] ws closed', flush=True)
                    break
                continue
        print(f'[{time.strftime("%H:%M:%S")}] total frames: {n}', flush=True)
asyncio.run(t())

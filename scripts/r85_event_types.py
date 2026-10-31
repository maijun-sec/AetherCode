"""R85 E2E: dump event types properly."""
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

import json
import time
from websockets.sync.client import connect

with connect('ws://127.0.0.1:17888/ws') as ws:
    ws.recv()
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':'say hi briefly, one word only'}}))
    end = time.time() + 30
    t0 = time.time()
    n = 0
    while time.time() < end:
        try:
            msg = ws.recv(timeout=2)
            n += 1
            d = json.loads(msg)
            elapsed = time.time() - t0
            params = d.get('params', {})
            ev = params.get('event', {}) if isinstance(params, dict) else {}
            et = ev.get('type')
            if et:
                if et == 'text_delta':
                    txt = ev.get('text', '')
                    print(f' [t={elapsed:.1f}s] text_delta {txt!r}')
                elif et == 'tool_use_start':
                    print(f' [t={elapsed:.1f}s] tool {ev.get("name")}')
                elif et == 'sub_task_start':
                    print(f' [t={elapsed:.1f}s] SUB_START {ev.get("subTaskId")} status={ev.get("status")} content={ev.get("content")!r}')
                elif et == 'sub_task_end':
                    print(f' [t={elapsed:.1f}s] SUB_END   {ev.get("subTaskId")} status={ev.get("status")} summary={ev.get("summary")!r}')
                elif et == 'run_end':
                    print(f' [t={elapsed:.1f}s] run_end reason={ev.get("stopReason")}')
                else:
                    print(f' [t={elapsed:.1f}s] {et}')
            else:
                print(f' [t={elapsed:.1f}s] frame#{n} (no event): {str(d)[:100]!r}')
        except TimeoutError:
            print(f' [t={time.time()-t0:.1f}s] (no frame in 2s)')

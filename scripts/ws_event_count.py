"""Count event types from a query."""
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

import json, time, sys
from collections import Counter
from websockets.sync.client import connect

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17890

counts = Counter()
with connect(f'ws://127.0.0.1:{PORT}/ws') as ws:
    welcome = ws.recv()
    print(f'[{time.strftime("%H:%M:%S")}] connected', flush=True)
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':
        'List files in D:/work/workspace/idea/engine/AetherCode/aethercode/aethercode-core/src/main/java '
        'AND D:/work/workspace/idea/engine/AetherCode/aethercode/aethercode-tools/src/main/java. '
        'Use todo_write with subtasks[] field to plan, then sub_todo_write to close each sub-task.'
    }}))
    print(f'[{time.strftime("%H:%M:%S")}] query sent', flush=True)
    end = time.time() + 90
    while time.time() < end:
        try:
            msg = ws.recv(timeout=2)
            d = json.loads(msg)
            if 'event' in d:
                et = d['event'].get('type', '?')
                counts[et] += 1
        except TimeoutError:
            continue

print(f'[{time.strftime("%H:%M:%S")}] done', flush=True)
for k, v in counts.most_common():
    print(f'  {k:30s} {v}', flush=True)
print(f'TOTAL EVENTS: {sum(counts.values())}', flush=True)

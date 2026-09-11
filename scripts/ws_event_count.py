"""Count event types from a query."""
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

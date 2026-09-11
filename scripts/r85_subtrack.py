"""R85 sub-task workflow: print all tool events + sub-task events."""
import json
import time
from websockets.sync.client import connect

prompt = (
    'List the first 5 files in D:/work/workspace/idea/engine/AetherCode/aethercode/aethercode-core/src/main/java '
    'AND D:/work/workspace/idea/engine/AetherCode/aethercode/aethercode-tools/src/main/java. '
    'You MUST use the todo_write tool with the subtasks[] field to plan your work '
    '(e.g. subtasks: [{id: "a", content: "List core files", status: "pending"}, '
    '{id: "b", content: "List tools files", status: "pending"}]). '
    'Then call sub_todo_write to mark each sub-task in_progress before doing the work, '
    'and completed with a summary when done.'
)

with connect('ws://127.0.0.1:17888/ws') as ws:
    print(f'[t=0] connected')
    ws.recv()  # welcome
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':prompt}}))
    print(f'[t=0] query sent')
    end = time.time() + 90
    t0 = time.time()
    n = 0
    sub_starts = []
    sub_ends = []
    tools = []
    while time.time() < end:
        try:
            msg = ws.recv(timeout=3)
            n += 1
            elapsed = time.time() - t0
            d = json.loads(msg)
            if 'event' in d:
                ev = d['event']
                et = ev.get('type')
                if et == 'sub_task_start':
                    sub_starts.append(ev)
                    print(f' [t={elapsed:.1f}s] SUB_START task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} content={ev.get("content")!r}')
                elif et == 'sub_task_end':
                    sub_ends.append(ev)
                    print(f' [t={elapsed:.1f}s] SUB_END task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} summary={ev.get("summary")!r}')
                elif et == 'tool_use_start':
                    name = ev.get('name')
                    tools.append(name)
                    if name in ('todo_write', 'sub_todo_write', 'bash', 'list_directory'):
                        print(f' [t={elapsed:.1f}s] TOOL {name}')
                elif et == 'text_delta':
                    pass
                elif et == 'run_end':
                    print(f' [t={elapsed:.1f}s] run_end reason={ev.get("stopReason")}')
        except TimeoutError:
            continue
    print(f'== total frames: {n} ==')
    print(f'== sub_starts: {len(sub_starts)} sub_ends: {len(sub_ends)} ==')
    print(f'== tools used: {set(tools)} ==')

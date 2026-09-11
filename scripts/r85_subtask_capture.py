"""R85 E2E: capture full sub_task_start / sub_task_end payload + tool events."""
import json
import time
from websockets.sync.client import connect

# A short prompt that should still trigger a sub-task workflow.
prompt = 'Greet me. Also write a one-sentence file D:/tmp/abc/hi.txt containing the greeting.'

with connect('ws://127.0.0.1:17888/ws') as ws:
    print('[t=0] connected')
    ws.recv()
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':prompt}}))
    print('[t=0] query sent')
    end = time.time() + 60
    t0 = time.time()
    n = 0
    while time.time() < end:
        try:
            msg = ws.recv(timeout=2)
            n += 1
            d = json.loads(msg)
            elapsed = time.time() - t0
            if 'event' in d:
                ev = d['event']
                et = ev.get('type')
                if et in ('sub_task_start', 'sub_task_end'):
                    # Pretty print the sub-task event
                    if et == 'sub_task_start':
                        print(f' [t={elapsed:.1f}s] SUB_TASK_START task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} content={ev.get("content")!r}')
                    else:
                        print(f' [t={elapsed:.1f}s] SUB_TASK_END   task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} summary={ev.get("summary")!r}')
                elif et == 'tool_use_start':
                    name = ev.get('name')
                    inp = ev.get('input', {})
                    inp_preview = json.dumps(inp)[:80] if isinstance(inp, dict) else str(inp)[:80]
                    print(f' [t={elapsed:.1f}s] TOOL  {name}  {inp_preview!r}')
                elif et == 'tool_result':
                    is_err = ev.get('isError')
                    content = ev.get('content', '')
                    preview = str(content)[:60] if content else ''
                    print(f' [t={elapsed:.1f}s] TOOL-RESULT  err={is_err}  {preview!r}')
                elif et == 'run_end':
                    print(f' [t={elapsed:.1f}s] run_end reason={ev.get("stopReason")}')
        except TimeoutError:
            continue
    print(f'== total frames: {n} ==')

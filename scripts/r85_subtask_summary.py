"""R85 E2E: try to make the model use sub-tasks with actual in_progress -> completed flow + summary."""
import json
import time
from websockets.sync.client import connect

# A prompt that strongly nudges the model to use sub-tasks with actual status transitions.
prompt = (
    'You have a 2-step task: (1) write "hello" to D:/tmp/abc/task1.log, '
    '(2) read it back. '
    'Use the todo_write tool first with content "two-step task" and '
    'subtasks: [{id:"write", content:"Write hello file", status:"pending"}, '
    '{id:"read", content:"Read file back", status:"pending"}]. '
    'Then for each sub-task: call sub_todo_write(parent_index=0, subtask_id=<id>, status="in_progress"), '
    'do the work, then call sub_todo_write(parent_index=0, subtask_id=<id>, status="completed", summary="...").'
)

with connect('ws://127.0.0.1:17888/ws') as ws:
    print('[t=0] connected')
    ws.recv()
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':prompt}}))
    print('[t=0] query sent')
    end = time.time() + 90
    t0 = time.time()
    n = 0
    while time.time() < end:
        try:
            msg = ws.recv(timeout=2)
            n += 1
            d = json.loads(msg)
            elapsed = time.time() - t0
            ev = d.get('params', {}).get('event', {}) if isinstance(d.get('params'), dict) else {}
            et = ev.get('type')
            if et == 'sub_task_start':
                print(f' [t={elapsed:.1f}s] SUB_START task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} content={ev.get("content")!r}')
            elif et == 'sub_task_end':
                print(f' [t={elapsed:.1f}s] SUB_END   task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} summary={ev.get("summary")!r}')
            elif et == 'tool_use_start':
                name = ev.get('name')
                inp = ev.get('input', {})
                if name in ('todo_write', 'sub_todo_write', 'bash', 'read_file', 'write_file'):
                    print(f' [t={elapsed:.1f}s] TOOL {name}  {json.dumps(inp)[:80]!r}')
            elif et == 'run_end':
                print(f' [t={elapsed:.1f}s] run_end reason={ev.get("stopReason")}')
            elif et == 'text_delta':
                pass  # skip
            elif et == 'side_note':
                pass  # skip
        except TimeoutError:
            continue
    print(f'== total frames: {n} ==')

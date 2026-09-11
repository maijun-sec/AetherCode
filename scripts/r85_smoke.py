"""Force a sub-task workflow via direct tool_use.
Sends a multi-step query that should exercise todo_write + sub_todo_write."""
import json
import time
import sys
from websockets.sync.client import connect

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17888

prompt = """MUST follow this workflow. No exceptions.

Step 1: Call todo_write with content "do two sub-tasks" and a single subtask
        list: subtasks=[{id:"a",content:"List files",status:"pending"},
                         {id:"b",content:"Count lines",status:"pending"}]
Step 2: Call sub_todo_write(parent_index=0, subtask_id="a", status="in_progress")
Step 3: Use bash to list files in D:/work/workspace/idea/engine/AetherCode/aethercode/aethercode-core/src/main/java
Step 4: Call sub_todo_write(parent_index=0, subtask_id="a", status="completed", summary="listed files")
Step 5: Call sub_todo_write(parent_index=0, subtask_id="b", status="in_progress")
Step 6: Use bash to count lines in those files
Step 7: Call sub_todo_write(parent_index=0, subtask_id="b", status="completed", summary="counted lines")

If you skip any step the test fails. Use ONLY the tools todo_write,
sub_todo_write, and bash. Don't write any explanatory text, just execute."""

with connect(f'ws://127.0.0.1:{PORT}/ws') as ws:
    print(f'[{time.strftime("%H:%M:%S")}] connected')
    ws.recv()
    ws.send(json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'query', 'params': {'prompt': prompt}}))
    print(f'[{time.strftime("%H:%M:%S")}] query sent')
    end = time.time() + 90
    n = 0
    sub_starts = 0
    sub_ends = 0
    todo_writes = 0
    sub_todo_writes = 0
    while time.time() < end:
        try:
            msg = ws.recv(timeout=2)
            n += 1
            d = json.loads(msg)
            if 'event' in d:
                ev = d['event']
                et = ev.get('type')
                if et == 'sub_task_start':
                    sub_starts += 1
                    print(f' [SUB_START] task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} content={ev.get("content")!r}', flush=True)
                elif et == 'sub_task_end':
                    sub_ends += 1
                    print(f' [SUB_END] task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} summary={ev.get("summary")!r}', flush=True)
                elif et == 'tool_use_start':
                    name = ev.get('name')
                    if name == 'todo_write':
                        todo_writes += 1
                        print(f' [TOOL] todo_write', flush=True)
                    elif name == 'sub_todo_write':
                        sub_todo_writes += 1
                        print(f' [TOOL] sub_todo_write', flush=True)
                elif et == 'run_end':
                    print(f' [run_end reason={ev.get("stopReason")}]', flush=True)
                elif et == 'text_delta':
                    preview = (ev.get('text','') or '').encode('ascii', 'replace')[:80].decode('ascii')
                    print(f' [text] {preview!r}', flush=True)
                elif et == 'side_note':
                    msg_text = ev.get('message', '')
                    print(f' [side_note] {msg_text!r}', flush=True)
            elif 'id' in d:
                print(f" [RPC id={d.get('id')}] result={str(d.get('result'))[:120]}", flush=True)
        except TimeoutError:
            continue
    print(f'== total frames: {n} ==')
    print(f'== sub_starts: {sub_starts} sub_ends: {sub_ends} ==')
    print(f'== todo_write: {todo_writes} sub_todo_write: {sub_todo_writes} ==')

"""Send a query that exercises sub-task workflow."""
import json, time, sys
from websockets.sync.client import connect

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17890

with connect(f'ws://127.0.0.1:{PORT}/ws') as ws:
    print(f'[{time.strftime("%H:%M:%S")}] connected + welcome', flush=True)
    ws.recv()  # welcome
    prompt = (
        'List files in D:/work/workspace/idea/engine/AetherCode/aethercode/aethercode-core/src/main/java '
        'AND D:/work/workspace/idea/engine/AetherCode/aethercode/aethercode-tools/src/main/java. '
        'Use todo_write with subtasks[] field to plan, then sub_todo_write to close each sub-task.'
    )
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':prompt}}))
    print(f'[{time.strftime("%H:%M:%S")}] query sent', flush=True)
    end = time.time() + 90
    n = 0
    sub_events = []
    while time.time() < end:
        try:
            msg = ws.recv(timeout=2)
            n += 1
            d = json.loads(msg)
            if 'event' in d:
                ev = d['event']
                et = ev.get('type')
                if et == 'sub_task_start':
                    sub_events.append(('START', ev.get('content'), ev.get('taskId'), ev.get('subTaskId'), ev.get('status')))
                    print(f' [sub_task_start] task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} content={ev.get("content")!r}', flush=True)
                elif et == 'sub_task_end':
                    sub_events.append(('END', ev.get('status'), ev.get('taskId'), ev.get('subTaskId'), ev.get('summary')))
                    print(f' [sub_task_end] task={ev.get("taskId")} sub={ev.get("subTaskId")} status={ev.get("status")} summary={ev.get("summary")!r}', flush=True)
                elif et == 'text_delta':
                    preview = (ev.get('text','') or '').encode('ascii', 'replace')[:60].decode('ascii')
                    print(f' [text_delta] {preview!r}', flush=True)
                elif et == 'run_start':
                    print(' [run_start]', flush=True)
                elif et == 'run_end':
                    print(f' [run_end reason={ev.get("stopReason")}]', flush=True)
                elif et in ('tool_use_start', 'tool_result'):
                    if et == 'tool_use_start':
                        print(f' [tool_use_start] name={ev.get("name")}', flush=True)
                    else:
                        content = ev.get('content')
                        preview = (str(content) if content else '').encode('ascii', 'replace')[:60].decode('ascii')
                        print(f' [tool_result] err={ev.get("isError")} preview={preview!r}', flush=True)
                else:
                    print(f' [{et}]', flush=True)
            elif 'id' in d:
                print(f" [RPC-OK] result={d.get('result')}", flush=True)
        except TimeoutError:
            continue
    print()
    print(f'== total frames: {n} ==')
    print(f'== sub_task events: {len(sub_events)} ==')
    for ev in sub_events:
        print(f'  {ev}')

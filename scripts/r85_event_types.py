"""R85 E2E: dump event types properly."""
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

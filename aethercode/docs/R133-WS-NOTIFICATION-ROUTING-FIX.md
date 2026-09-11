# R133 SHIPPED (2026-08-20) — WebSocket inbound notification routing fix

## What broke

The RAG end-to-end test against the R132 daemon (PID 23616) revealed that
**medium-risk tool calls (`file_write`, `file_edit`, `todo_write`) never
completed**. The model would call the tool, the daemon would send a
`permission_request` notification, the headless driver would reply with a
`permissionResponse` notification (per JSON-RPC 2.0 fire-and-forget convention
with no `id` field), and then... nothing. The model would loop in thinking for
~1000s until `loop_detected` killed the run.

## Root cause

`HttpJsonRpcServer.handleMessage` (R80) had this branch for inbound
notifications:

```java
} else if (msg instanceof JsonRpcNotification notif) {
    // The engine never expects inbound notifications, but
    // pass through for protocol completeness.
    LOG.debug("ws {} sent notification: {}", connId, notif.method());
}
```

The comment was technically true for the TUI/Desktop clients (they use
`this.call()` which sends a `JsonRpcRequest` with an `id` field, so the
request path handled them correctly) but **wrong for the headless Python
driver** which followed the JSON-RPC spec and sent `permissionResponse` as a
fire-and-forget notification.

The result:
1. Prompter created a `CompletableFuture<PermissionDecision>` for the ask
2. Daemon sent `permission_request` notification to client
3. Client replied with `permissionResponse` notification
4. Daemon LOG.debug'd the message and dropped it on the floor
5. The future never resolved, the tool never executed, the model waited
6. After ~1000s the model hit `loop_detected`

## The fix

Route inbound notifications through the same `dispatch()` switch that
requests use. The switch already has cases for every inbound notification
method we know about (`permissionResponse`, `loopAck`,
`setAutoApproveLowRisk`, `setAutoApproveMediumHigh`), so the result is
discarded (notifications don't generate responses per the spec).

```java
} else if (msg instanceof JsonRpcNotification notif) {
    // R133-HOTFIX: route inbound notifications through dispatch()
    // too. (full comment in source)
    try {
        Object result = dispatch(notif.method(), notif.params());
        LOG.debug("ws {} notification {} ok: {}",
                connId, notif.method(),
                result == null ? "null" : result.getClass().getSimpleName());
    } catch (JsonRpcProtocolException jpe) {
        LOG.warn("ws {} notification {} protocol error: {}",
                connId, notif.method(), jpe.error().message());
    } catch (Throwable t) {
        LOG.warn("ws {} notification {} failed: {}",
                connId, notif.method(), t.getMessage());
    }
}
```

## Why this is R133 and not a R132 patch

The bug was latent in R80 (the original HTTP+WS server) and survived through
R126, R127, R128, R129, R130, R131, and R132 because:
- All TUI/Desktop/IDE clients use `this.call()` (request-with-id)
- All unit tests use the stdio JSON-RPC transport (which uses a real
  `JsonRpcDispatcher` that handles notifications correctly)
- The only client that exercised the WS-notification path was the
  headless Python driver, and that driver was added in R126 for the
  end-to-end test — which is the first time we hit the path in anger

The RAG end-to-end test in this session is what surfaced the bug.

## Tests (3 new)

`HttpJsonRpcServerR133Test` — uses JDK 11+ `HttpClient.WebSocket.Builder`
to drive a real WebSocket against a live `HttpJsonRpcServer`:

1. **`permissionResponseNotificationResolvesFuture`** — the exact bug:
   open WS, call `askPermission()`, send `permissionResponse`
   notification, assert future resolves within 2s with `isAllow()=true`.
   Before the fix, the future would have hung until the default timeout.

2. **`pingRequestReturnsPong`** — sanity that the request path still
   works (the WS layer wasn't broken, only the notification branch).

3. **`unknownNotificationIsIgnored`** — guards against a future where the
   server adds a new notification method the client doesn't know about.
   Server should log+drop, not crash.

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 131, Failures: 0, Errors: 0, Skipped: 0
  in aethercode-protocol (full module)
```

## Regression check

Full `mvn -B test` against all 17 modules:

```
[ERROR] Tests run: 881, Failures: 1, Errors: 0, Skipped: 0
```

The 1 failure is a pre-existing flake
(`SubagentPoolTest.submit_multipleConcurrently` — timing race, 5s timeout,
last modified 8/6) unrelated to R133. Confirmed by the failure being
identical pre- and post-fix.

## Impact on RAG end-to-end

With this fix, the RAG end-to-end driver can now:
- Use `file_write` for any size of multi-file module
- Skip the PowerShell heredoc / base64 workaround
- The model no longer hits `loop_detected` waiting for tool results

Before R133 the headless driver had to route every file through bash
(`echo > file`, `cat << EOF`) because `file_write` never returned a result.
Now `file_write` works end-to-end.

## Files

- `aethercode-protocol/src/main/java/.../http/HttpJsonRpcServer.java` —
  R133-HOTFIX comment + notification routing (~25 lines)
- `aethercode-protocol/src/test/java/.../http/HttpJsonRpcServerR133Test.java`
  — 3 new tests (11561 bytes)
- `D:\tmp\aethercode-r133\aethercode.jar` (53.6 MB) — R133 build
- `D:\tmp\aethercode-r133\ac-tui.exe` (99.7 MB) — TUI
- `D:\tmp\aethercode-r133\aethercode-desktop.exe` (3.7 MB) — Desktop
- `D:\tmp\aethercode-r133-release.zip` — full release

## Cumulative test counts

- **Java**: 2232 tests (R132 2229 + R133 3), 0 failures
  (1 pre-existing flake in `SubagentPoolTest`)
- **TS**: 430 tests, 0 failures
- **0 new regressions**

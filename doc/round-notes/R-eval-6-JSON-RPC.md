# R-eval-6 AetherCode JSON-RPC Interface Conformance (2026-09-12)

## 触发

R-eval master plan 第 6 round。AetherCode 前端 (TUI / desktop / CLI)
调后端通过 JSON-RPC 2.0 over daemon 的 stdio / websocket. Protocol
9 个 endpoint 家族: memory/* (6) / compact/* / context/* / engine/continuation
/ grant/* / permission/* / task/* / theme/*.

R-eval-6 写 self-contained JsonRpcRequest/Response/Dispatcher + 9 个
endpoint family mock, 35 个 test 覆盖请求/响应/错误/路由/审计.

## 实际产出 (1 test class, 35 tests, 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/capability/interface_/JsonRpcInterfaceTest.java`
  (28 KB), 35 tests:
  - **Memory endpoints** (6): get/append project/append session/compact/
    switch project/list
  - **Compact** (1): force flag
  - **Context** (2): get default / set+get roundtrip
  - **Engine continuation** (2): requires sessionId / records sessionId
  - **Grant** (3): list / revoke requires id / revoke records op
  - **Permission** (2): check / request records pending
  - **Task** (3): list / status / cancel
  - **Theme** (3): list / set unknown rejected / set switches active
  - **Method-not-found** (2): unknown method / alias resolution
  - **JSON-RPC 2.0 wire format** (4): version default / id auto / id
    preserved / 2.0
  - **Call log / observability** (2): records every dispatch /
    hasMethod
  - **Validation** (4): null request / blank method / handler exception
    is internal / IAE is invalid params
  - **E2E lifecycle** (1): 6-call session roundtrip
- **aethercode-evals: 552/552 Java tests pass** (517 R-eval-5 + 35 new
  R-eval-6), 0 回归

## 关键技术决定 (8 条)

1. **JSON-RPC 2.0 wire format** - jsonrpc / id / method / params /
   result / error, 跟 aethercode-protocol 1:1
2. **9 endpoint family mock** - 跟 AetherCodeMethods / MemoryMethods /
   CompactMethods / ContextMethods / EngineContinuationDispatcher /
   GrantMethods / PermissionMethods / TaskMethods / ThemeMethods 一致
3. **error code 跟 JSON-RPC 2.0 spec** - -32700 (parse) / -32600
   (invalid request) / -32601 (method not found) / -32602
   (invalid params) / -32603 (internal)
4. **IAE 映射 invalid params** - handler 抛 IAE 自动包成 -32602
5. **Exception 映射 internal error** - 其他 exception 包成 -32603
6. **Call log 内置** - 每个 dispatch 记录, 调试 / 审计
7. **Alias 机制** - 从 from method 重定向到 to method
8. **Test seam 不依赖真 protocol** - R-mod-2 后 wire 真 JsonRpcDispatcher

## 9 endpoint + 35 tests 映射

| Endpoint family | Tests | AetherCode 映射 |
|---|---|---|
| memory/* | 6 | MemoryMethods |
| compact/* | 1 | CompactMethods |
| context/* | 2 | ContextMethods |
| engine/continuation | 2 | EngineContinuationDispatcher |
| grant/* | 3 | GrantMethods |
| permission/* | 2 | PermissionMethods |
| task/* | 3 | TaskMethods |
| theme/* | 3 | ThemeMethods |
| Cross-cutting | 13 | JsonRpcDispatcher + 错误 |

## API shape (JsonRpcDispatcher)

```java
JsonRpcDispatcher d = new JsonRpcDispatcher();
d.register("memory/get", params -> {
    return Map.of("entries", List.of(...), "tokens", 100);
});
d.alias("theme/current", "theme/list");  // 重定向

JsonRpcRequest req = new JsonRpcRequest(null, "1", "memory/get", Map.of("scope", "PROJECT"));
JsonRpcResponse r = d.dispatch(req);
if (r.ok()) {
    System.out.println(r.result());
} else {
    System.out.println("error: " + r.error().code() + " " + r.error().message());
}
```

## 累计测试 (R-eval-6 后)

- aethercode-orchestration: 0
- aethercode-evals: **552/552** (517 R-eval-5 + 35 R-eval-6)
- 其他不变
- 0 回归

## 教训 (新增 8 条, 累计 283+)

276. **JSON-RPC 2.0 wire format 1:1** - jsonrpc / id / method / params
     / result / error
277. **9 endpoint family mock 跟 AetherCode 一致** - memory/compact/
     context/engine/grant/permission/task/theme
278. **Error code 跟 JSON-RPC 2.0 spec** - -32700 / -32600 / -32601 /
     -32602 / -32603
279. **IAE → invalid params** - handler 抛 IAE 自动包 -32602
280. **Exception → internal error** - 其他 exception 包 -32603
281. **Call log 内置** - 调试 / 审计
282. **Alias 机制** - from method 重定向 to method
283. **Test seam 不依赖真 protocol** - R-mod-2 后 wire

## 后续

- R-eval-7: A2A Multi-Agent
- R-eval-8: Cost-Efficiency, Safety & Robustness

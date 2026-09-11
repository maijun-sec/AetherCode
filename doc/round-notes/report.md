# R234 Daemon End-to-End Test Report

- Daemon port: **17962**
- Total cases: **104**
- Pass: **104**
- Fail: **0**

## Per-RPC summary

| RPC | Count | Pass | Fail |
|-----|------:|-----:|-----:|
| `addSkill` | 3 | 3 | 0 |
| `addWorktree` | 1 | 1 | 0 |
| `cancel` | 1 | 1 | 0 |
| `compact` | 1 | 1 | 0 |
| `compressProjectMemory` | 1 | 1 | 0 |
| `createAgent` | 1 | 1 | 0 |
| `createEngine` | 1 | 1 | 0 |
| `createSession` | 1 | 1 | 0 |
| `createTask` | 1 | 1 | 0 |
| `deleteAgent` | 1 | 1 | 0 |
| `deleteEngine` | 1 | 1 | 0 |
| `deleteMemory` | 1 | 1 | 0 |
| `deleteSession` | 1 | 1 | 0 |
| `deleteWorkflow` | 1 | 1 | 0 |
| `engineHealth` | 1 | 1 | 0 |
| `getActiveEngine` | 1 | 1 | 0 |
| `getAgentBody` | 3 | 3 | 0 |
| `getContextInfo` | 1 | 1 | 0 |
| `getEngineStats` | 1 | 1 | 0 |
| `getMemory` | 2 | 2 | 0 |
| `getMetrics` | 1 | 1 | 0 |
| `getPermissionModeSuggestion` | 1 | 1 | 0 |
| `getPermissionStatus` | 1 | 1 | 0 |
| `getPhaseBudget` | 1 | 1 | 0 |
| `getSkillBody` | 3 | 3 | 0 |
| `getSkipStats` | 1 | 1 | 0 |
| `getState` | 1 | 1 | 0 |
| `getSystemPrompt` | 1 | 1 | 0 |
| `getSystemPromptSection` | 1 | 1 | 0 |
| `getTrace` | 1 | 1 | 0 |
| `getTraces` | 1 | 1 | 0 |
| `getTranscript` | 1 | 1 | 0 |
| `getWorkflow` | 3 | 3 | 0 |
| `healthCheckChild` | 1 | 1 | 0 |
| `http.api.methods` | 1 | 1 | 0 |
| `http.healthz` | 1 | 1 | 0 |
| `listAgents` | 1 | 1 | 0 |
| `listChildren` | 1 | 1 | 0 |
| `listCwdFiles` | 1 | 1 | 0 |
| `listEngines` | 1 | 1 | 0 |
| `listMemory` | 1 | 1 | 0 |
| `listModels` | 1 | 1 | 0 |
| `listProjects` | 1 | 1 | 0 |
| `listProviders` | 1 | 1 | 0 |
| `listSessions` | 1 | 1 | 0 |
| `listSkills` | 4 | 4 | 0 |
| `listTasks` | 2 | 2 | 0 |
| `listToolActions` | 1 | 1 | 0 |
| `listTools` | 1 | 1 | 0 |
| `listWorkflows` | 2 | 2 | 0 |
| `listWorktrees` | 1 | 1 | 0 |
| `loadSession` | 2 | 2 | 0 |
| `loopAck` | 1 | 1 | 0 |
| `ping` | 1 | 1 | 0 |
| `query` | 1 | 1 | 0 |
| `registerChild` | 1 | 1 | 0 |
| `reloadAgents` | 1 | 1 | 0 |
| `reloadRegistries` | 1 | 1 | 0 |
| `reloadSkills` | 1 | 1 | 0 |
| `removeWorktree` | 1 | 1 | 0 |
| `retrySubTask` | 1 | 1 | 0 |
| `runWorkflow` | 1 | 1 | 0 |
| `setActiveEngine` | 2 | 2 | 0 |
| `setAutoApproveLowRisk` | 1 | 1 | 0 |
| `setAutoApproveMediumHigh` | 1 | 1 | 0 |
| `setAutoRestart` | 1 | 1 | 0 |
| `setConcurrencyProfile` | 1 | 1 | 0 |
| `setContinuationStopped` | 1 | 1 | 0 |
| `setLoopDetectorThresholds` | 1 | 1 | 0 |
| `setMemory` | 1 | 1 | 0 |
| `setModel` | 1 | 1 | 0 |
| `setPermissionMode` | 2 | 2 | 0 |
| `setPhase` | 1 | 1 | 0 |
| `setPhaseBudget` | 1 | 1 | 0 |
| `setSkipConfirmation` | 1 | 1 | 0 |
| `setSystemPrompt` | 1 | 1 | 0 |
| `subagentCancel` | 1 | 1 | 0 |
| `summary` | 1 | 1 | 0 |
| `switchProject` | 1 | 1 | 0 |
| `switchProvider` | 1 | 1 | 0 |
| `unregisterChild` | 1 | 1 | 0 |
| `updateAgent` | 1 | 1 | 0 |
| `updateTaskStatus` | 1 | 1 | 0 |
| `viewAuditLog` | 1 | 1 | 0 |
| `writeWorkflow` | 2 | 2 | 0 |
| `fs.check` | 1 | 1 | 0 |

## Daemon lifecycle

### `engineHealth`

#### Case: `engineHealth_basic` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "healthy": true, "version": null, "uptimeMs": 253553, "model": "MiniMax-M3", "sessionId": "default", "permissionMode": "DEFAULT", "state": "stop", "lastError": "", "lastErrorAtMs": 0, "lastErrorForMs": -1, "lastActivityAtMs": 1788832417088, "idleForMs": 67652, "lastPingAtMs": 1788832484740, "lastPingForMs": 0, "queries": 1, "totalToolCalls": 0, "pendingPermissionCount": 0}`

### `getState`

#### Case: `getState_basic` — ✅

- **Input**: `{}`
- **Result**: `{"sessionId": "default", "model": "MiniMax-M3", "permissionMode": "DEFAULT", "toolCount": 19, "tools": ["file_read", "file_write", "file_edit", "glob", "grep", "bash", "todo_write", "sub_todo_write", "spawn_agent", "subagent_status", "subagent_list", "web_fetch", "web_search", "notebook_edit", "ask_user_question", "wm_put", "wm_get", "wm_list", "wm_clear"], "transcriptSize": 18, "contextWindow"...`

### `http.api.methods`

#### Case: `method_list_size` — ✅

- **Input**: `null`
- **Result**: `{"methods": [{"name": "ping", "tags": ["read", "diagnostic"]}, {"name": "engineHealth", "tags": ["read", "diagnostic"]}, {"name": "getMetrics", "tags": ["read", "diagnostic"]}, {"name": "getTraces", "tags": ["read", "diagnostic"]}, {"name": "getTrace", "tags": ["read", "diagnostic"]}, {"name": "getEngineStats", "tags": ["read", "diagnostic"]}, {"name": "getState", "tags": ["read", "engine"]}, {...`

### `http.healthz`

#### Case: `healthz_ok` — ✅

- **Input**: `null`
- **Result**: `"{\"status\":\"ok\",\"uptimeMs\":253564,\"version\":\"unknown\",\"sessionId\":\"default\"}"`

### `ping`

#### Case: `ping_basic` — ✅

- **Input**: `{}`
- **Result**: `{"version": null, "uptimeMs": 253551, "model": "MiniMax-M3", "sessionId": "default"}`

## Skill registry (two-tier loading)

### `addSkill`

#### Case: `addSkill_project` — ✅

- **Input**: `{"name": "r234-test-skill", "scope": "PROJECT", "body": "---\nname: r234-test-skill\ndescription: R234 dynamic addSkill test.\n---\n# R234 test body marker\n"}`
- **Result**: `{"name": "r234-test-skill", "scope": "PROJECT", "count": 6, "ok": true, "path": "D:\\work\\workspace\\idea\\engine\\AetherCode\\.aethercode\\skills\\r234-test-skill\\SKILL.md", "reloadedAt": 1788832484780}`

#### Case: `addSkill_unsafe_name_rejected` — ✅

- **Input**: `{"name": "../escape", "scope": "PROJECT", "body": "x"}`
- **Result**: `{"ok": false, "error": "invalid name \"../escape\" (allowed: must start with a letter or digit, then letters / digits / '.', '_', '-')"}`

#### Case: `addSkill_global` — ✅

- **Input**: `{"name": "r234-global-skill", "scope": "GLOBAL", "body": "---\nname: r234-global-skill\ndescription: R234 global addSkill test.\n---\n# R234 global body marker\n"}`
- **Result**: `{"name": "r234-global-skill", "scope": "GLOBAL", "count": 6, "ok": true, "path": "C:\\Users\\maijun\\.aethercode\\skills\\r234-global-skill\\SKILL.md", "reloadedAt": 1788832484807}`

### `fs.check`

#### Case: `global_skill_on_disk` — ✅

- **Input**: `{"path": "C:\\Users\\maijun/.aethercode/skills/r234-global-skill/SKILL.md"}`
- **Result**: `{"exists": true}`

### `getSkillBody`

#### Case: `getSkillBody_sample` — ✅

- **Input**: `{"name": "sample-skill"}`
- **Result**: `{"path": "D:\\work\\workspace\\idea\\engine\\AetherCode\\.aethercode\\skills\\sample-skill\\SKILL.md", "body": "# Sample Skill\n\nThis is the body of the sample-skill. It should NOT be loaded eagerly —\nthe registry should only show the description in `listSkills` and only\nreturn the body when `getSkillBody(\"sample-skill\")` is called.\n\nThe body demonstrates that the two-tier loading is cor...`

#### Case: `getSkillBody_missing` — ✅

- **Input**: `{"name": "no-such-skill-zzz"}`
- **Result**: `{"ok": false, "name": "no-such-skill-zzz", "error": "skill not found"}`

#### Case: `getSkillBody_after_add` — ✅

- **Input**: `{"name": "r234-test-skill"}`
- **Result**: `{"path": "D:\\work\\workspace\\idea\\engine\\AetherCode\\.aethercode\\skills\\r234-test-skill\\SKILL.md", "body": "# R234 test body marker\n", "lastModifiedMs": 1788832484770, "name": "r234-test-skill", "ok": true}`

### `listSkills`

#### Case: `listSkills_baseline` — ✅

- **Input**: `{}`
- **Result**: `{"names": ["another-skill", "r234-global-skill", "r234-quick", "r234-test-skill", "sample-skill", "user-skill"], "missing": []}`

#### Case: `two_tier_no_body_in_list` — ✅

- **Input**: `{}`
- **Result**: `{"skills": [{"name": "another-skill", "description": "Another test skill, exercises the multi-skill scan and ordering.", "displayNameZhHans": "", "displayName": "Another Skill", "descriptionZhHans": "", "source": "project", "lastModifiedMs": 1788790750654}, {"name": "r234-global-skill", "description": "R234 global addSkill test.", "displayNameZhHans": "", "displayName": "", "descriptionZhHans":...`

#### Case: `listSkills_after_add` — ✅

- **Input**: `{}`
- **Result**: `{"skills": [{"name": "another-skill", "description": "Another test skill, exercises the multi-skill scan and ordering.", "displayNameZhHans": "", "displayName": "Another Skill", "descriptionZhHans": "", "source": "project", "lastModifiedMs": 1788790750654}, {"name": "r234-global-skill", "description": "R234 global addSkill test.", "displayNameZhHans": "", "displayName": "", "descriptionZhHans":...`

#### Case: `two_tier_no_body_in_list` — ✅

- **Input**: `{}`
- **Result**: `{"skills": [{"name": "another-skill", "description": "Another test skill, exercises the multi-skill scan and ordering.", "displayNameZhHans": "", "displayName": "Another Skill", "descriptionZhHans": "", "source": "project", "lastModifiedMs": 1788790750654}, {"name": "r234-global-skill", "description": "R234 global addSkill test.", "displayNameZhHans": "", "displayName": "", "descriptionZhHans":...`

### `reloadRegistries`

#### Case: `reloadRegistries` — ✅

- **Input**: `{}`
- **Result**: `{"mcpReloaded": true, "mcpAdded": 0, "mcpChanged": 0, "errors": [], "atMs": 1788832484809, "ok": true, "skillsCount": 6, "agentsCount": 1, "kind": "ALL", "mcpRemoved": 0}`

### `reloadSkills`

#### Case: `reloadSkills_idempotent` — ✅

- **Input**: `{}`
- **Result**: `{"count": 6, "ok": true, "reloadedAt": 1788832484792}`

## Workflow editor / runner

### `deleteWorkflow`

#### Case: `deleteWorkflow_ok` — ✅

- **Input**: `{"name": "r234-test"}`
- **Result**: `{"ok": true, "name": "r234-test", "removed": true}`

### `getWorkflow`

#### Case: `getWorkflow_yaml` — ✅

- **Input**: `{"name": "r234-test"}`
- **Result**: `{"ok": true, "name": "r234-test", "description": "R234 workflow test", "inputs": [], "steps": [], "stepCount": 0, "raw": "name: r234-test\ndescription: R234 workflow test\nsteps:\n  - kind: shell\n    name: echo-step\n    command: 'echo hello from r234'\n", "content": "name: r234-test\ndescription: R234 workflow test\nsteps:\n  - kind: shell\n    name: echo-step\n    command: 'echo hello from r...`

#### Case: `getWorkflow_after_overwrite` — ✅

- **Input**: `{"name": "r234-test"}`
- **Result**: `{"ok": true, "name": "r234-test", "description": "R234 workflow test", "inputs": [], "steps": [], "stepCount": 0, "raw": "name: r234-test\ndescription: R234 workflow test\nsteps:\n  - kind: shell\n    name: echo-step\n    command: 'echo hello from r234 v2'\n", "content": "name: r234-test\ndescription: R234 workflow test\nsteps:\n  - kind: shell\n    name: echo-step\n    command: 'echo hello fro...`

#### Case: `getWorkflow_after_delete` — ✅

- **Input**: `{"name": "r234-test"}`
- **Result**: `{"ok": false, "reason": "workflow not found: r234-test"}`

### `listCwdFiles`

#### Case: `listCwdFiles` — ✅

- **Input**: `{"prefix": "scri", "limit": 10}`
- **Result**: `{"files": [".aethercode/daemon-logs/daemon-port-17954.log.err", ".aethercode/daemon-logs/daemon-port-17954.log.out", ".aethercode/daemon-logs/daemon-port-17954.log.start", ".aethercode/daemon-logs/daemon-port-17955.log.err", ".aethercode/daemon-logs/daemon-port-17955.log.out", ".aethercode/daemon-logs/daemon-port-17956.log.err", ".aethercode/daemon-logs/daemon-port-17956.log.out", ".aethercode/...`

### `listWorkflows`

#### Case: `listWorkflows_initial` — ✅

- **Input**: `{}`
- **Result**: `{"workflows": [{"name": "explain-code", "description": "4 步解释：read → summarise → clarify → final", "inputs": ["file_path", "note"], "steps": [{"id": "read", "type": "shell", "continueOnError": false}, {"id": "summarise", "type": "agent", "continueOnError": false}, {"id": "clarify", "type": "agent", "continueOnError": false}, {"id": "final", "type": "gate", "continueOnError": false}], "stepCount...`

#### Case: `listWorkflows_after_write` — ✅

- **Input**: `{}`
- **Result**: `{"workflows": [{"name": "explain-code", "description": "4 步解释：read → summarise → clarify → final", "inputs": ["file_path", "note"], "steps": [{"id": "read", "type": "shell", "continueOnError": false}, {"id": "summarise", "type": "agent", "continueOnError": false}, {"id": "clarify", "type": "agent", "continueOnError": false}, {"id": "final", "type": "gate", "continueOnError": false}], "stepCount...`

### `runWorkflow`

#### Case: `runWorkflow_echo` — ✅

- **Input**: `{"name": "r234-test"}`
- **Result**: `{"ok": true, "runId": "wf-13", "name": "r234-test", "stepCount": 0, "accepted": true, "note": "R103 executor running on background thread"}`

### `writeWorkflow`

#### Case: `writeWorkflow_new` — ✅

- **Input**: `{"name": "r234-test", "content": "name: r234-test\ndescription: R234 workflow test\nsteps:\n  - kind: shell\n    name: echo-step\n    command: 'echo hello from r234'\n", "scope": "PROJECT"}`
- **Result**: `{"ok": true, "path": "D:\\work\\workspace\\idea\\engine\\AetherCode\\.aethercode\\workflows\\r234-test.yaml", "name": "r234-test"}`

#### Case: `writeWorkflow_overwrite` — ✅

- **Input**: `{"name": "r234-test", "content": "name: r234-test\ndescription: R234 workflow test\nsteps:\n  - kind: shell\n    name: echo-step\n    command: 'echo hello from r234 v2'\n", "scope": "PROJECT"}`
- **Result**: `{"ok": true, "path": "D:\\work\\workspace\\idea\\engine\\AetherCode\\.aethercode\\workflows\\r234-test.yaml", "name": "r234-test"}`

## Tool pool & per-tool permissions

### `getContextInfo`

#### Case: `getContextInfo` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "provider": "minmax", "model": "MiniMax-M3", "contextWindow": 1000000, "maxOutputTokens": 512000, "transcriptChars": 1668, "estimatedTokens": 417, "usagePercent": 0}`

### `listToolActions`

#### Case: `listToolActions_default` — ✅

- **Input**: `{}`
- **Result**: `{"sessionId": "default", "tools": [{"name": "file_read", "description": "Read a file from disk. Returns the file's content with 1-indexed line numbers. Use offset/limit to read a slice. Image files (PNG/JPG/GIF/WebP) are returned as a base64 attachment with the correct mime type.", "defaultOpKind": "READ", "samplePath": "src/main/java/Foo.java", "isReadOnly": false, "defaultAction": "ALLOW", "i...`

### `listTools`

#### Case: `listTools_default` — ✅

- **Input**: `{}`
- **Result**: `{"sessionId": "default", "tools": [{"name": "file_read", "description": "Read a file from disk. Returns the file's content with 1-indexed line numbers. Use offset/limit to read a slice. Image files (PNG/JPG/GIF/WebP) are returned as a base64 attachment with the correct mime type."}, {"name": "file_write", "description": "Write a file. Replaces the existing content. Refuses to write outside the ...`

## Mavis agent registry

### `createAgent`

#### Case: `createAgent` — ✅

- **Input**: `{"name": "r234-created-agent", "body": "---\nname: r234-created-agent\ndescription: created in test\n---\ncreated\n"}`
- **Result**: `{"ok": true, "name": "r234-created-agent"}`

### `deleteAgent`

#### Case: `deleteAgent` — ✅

- **Input**: `{"name": "r234-created-agent"}`
- **Result**: `{"ok": true, "name": "r234-created-agent"}`

### `getAgentBody`

#### Case: `getAgentBody_r234` — ✅

- **Input**: `{"name": "r234-test-agent"}`
- **Result**: `{"displayName": "", "body": "# R234 test agent\n", "path": "C:\\Users\\maijun\\.aethercode\\agents\\r234-test-agent\\agent.md", "lastModifiedMs": 1788829110448, "model": "", "name": "r234-test-agent", "ok": true, "description": "R234 agent test"}`

#### Case: `getAgentBody_after_update` — ✅

- **Input**: `{"name": "r234-created-agent"}`
- **Result**: `{"displayName": "", "body": "---\nname: r234-created-agent\ndescription: updated\n---\nupdated\n", "path": "C:\\Users\\maijun\\.aethercode\\agents\\r234-created-agent\\agent.md", "lastModifiedMs": 1788832488045, "model": "", "name": "r234-created-agent", "ok": true, "description": ""}`

#### Case: `getAgentBody_after_delete` — ✅

- **Input**: `{"name": "r234-created-agent"}`
- **Result**: `{"ok": false, "name": "r234-created-agent", "error": "agent not found"}`

### `listAgents`

#### Case: `listAgents_after_reload` — ✅

- **Input**: `{}`
- **Result**: `{"count": 1, "ok": true, "agents": [{"model": "", "lastModifiedMs": 1788829110448, "displayName": "", "name": "r234-test-agent", "description": "R234 agent test"}]}`

### `reloadAgents`

#### Case: `reloadAgents` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "count": 1}`

### `updateAgent`

#### Case: `updateAgent` — ✅

- **Input**: `{"name": "r234-created-agent", "body": "---\nname: r234-created-agent\ndescription: updated\n---\nupdated\n"}`
- **Result**: `{"ok": true, "name": "r234-created-agent"}`

## Memory (3-layer + audit log)

### `compressProjectMemory`

#### Case: `compressProjectMemory` — ✅

- **Input**: `{}`
- **Result**: `{"ok": false, "reason": "memory not configured"}`

### `deleteMemory`

#### Case: `deleteMemory` — ✅

- **Input**: `{"key": "r234-test-key"}`
- **Result**: `{"ok": false, "reason": "memory not configured"}`

### `getMemory`

#### Case: `getMemory` — ✅

- **Input**: `{"key": "r234-test-key"}`
- **Result**: `{"ok": false, "reason": "memory not configured"}`

#### Case: `getMemory_after_set` — ✅

- **Input**: `{"key": "r234-test-key"}`
- **Result**: `{"ok": false, "reason": "memory not configured"}`

### `listMemory`

#### Case: `listMemory` — ✅

- **Input**: `{}`
- **Result**: `{"ok": false, "reason": "memory not configured"}`

### `setMemory`

#### Case: `setMemory` — ✅

- **Input**: `{"key": "r234-test-key", "value": "r234-value"}`
- **Result**: `{"ok": false, "reason": "memory not configured"}`

### `viewAuditLog`

#### Case: `viewAuditLog` — ✅

- **Input**: `{"limit": 10}`
- **Result**: `{"path": "", "entries": [], "note": "audit not initialised", "count": 0, "ok": true}`

## Session manager (multi-engine)

### `createSession`

#### Case: `createSession_new` — ✅

- **Input**: `{"cwd": "D:/work/workspace/idea/engine/AetherCode"}`
- **Result**: `{"cwd": "D:/work/workspace/idea/engine/AetherCode", "messageCount": 0, "active": "2026-09-08T01-54-48.082701800Z_4aca9f43", "worktree": "", "ok": true, "sessionId": "2026-09-08T01-54-48.082701800Z_4aca9f43"}`

### `deleteSession`

#### Case: `deleteSession` — ✅

- **Input**: `{"sessionId": "2026-09-08T01-54-48.082701800Z_4aca9f43"}`
- **Result**: `{"ok": true, "sessionId": "2026-09-08T01-54-48.082701800Z_4aca9f43", "removed": true}`

### `getTranscript`

#### Case: `getTranscript` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "sessionId": "default", "messages": [{"id": "4f638296-f88f-4959-9dfb-e1bd916aef84", "role": "user", "content": [{"type": "text", "text": "[R89 闁插秷鐦€涙劒鎹㈤崝顡?r234 smoke retry"}], "timestamp": 1788829823578, "metadata": {}}, {"id": "7210e737-b32d-47c4-9164-6b5260542ed3", "role": "assistant", "content": [{"type": "text", "text": "<think>The user sent what appears to be a test/retry pro...`

### `listSessions`

#### Case: `listSessions` — ✅

- **Input**: `{}`
- **Result**: `{"returned": 34, "current": "default", "total": 34, "sessions": [{"id": "default", "lastUsedAt": 1788832417083, "sizeBytes": 4791, "messageCount": 5}, {"id": "r234-test-1788832415", "lastUsedAt": 1788832415238, "sizeBytes": 0, "messageCount": 1}, {"id": "r234-test-1788832366", "lastUsedAt": 1788832366620, "sizeBytes": 0, "messageCount": 1}, {"id": "r234-test-1788832316", "lastUsedAt": 178883231...`

### `loadSession`

#### Case: `loadSession` — ✅

- **Input**: `{"sessionId": "2026-09-08T01-54-48.082701800Z_4aca9f43"}`
- **Result**: `{"messageCount": 0, "ok": true, "sessionId": "2026-09-08T01-54-48.082701800Z_4aca9f43"}`

#### Case: `loadSession_default` — ✅

- **Input**: `{"sessionId": "default"}`
- **Result**: `{"messageCount": 18, "ok": true, "sessionId": "default"}`

## Task board (Kanban)

### `createTask`

#### Case: `createTask` — ✅

- **Input**: `{"description": "R234 test task", "status": "todo", "title": "R234"}`
- **Result**: `{"ok": true, "task": {"id": "u-kkvutoni", "type": "user", "status": "pending", "description": "R234 test task", "parentTaskId": null, "createdAtMs": 1788832488287, "endedAtMs": 0}}`

### `listTasks`

#### Case: `listTasks_initial` — ✅

- **Input**: `{}`
- **Result**: `{"tasks": [{"id": "u-34hp8iks", "type": "user", "status": "pending", "description": "R234 test task", "parentTaskId": null, "createdAtMs": 1788832251153, "endedAtMs": 0}, {"id": "u-0tiruic4", "type": "user", "status": "completed", "description": "say ok", "parentTaskId": null, "createdAtMs": 1788832251423, "endedAtMs": 1788832257906}, {"id": "u-gjcocuob", "type": "user", "status": "running", "d...`

#### Case: `listTasks_after_create` — ✅

- **Input**: `{}`
- **Result**: `{"tasks": [{"id": "u-34hp8iks", "type": "user", "status": "pending", "description": "R234 test task", "parentTaskId": null, "createdAtMs": 1788832251153, "endedAtMs": 0}, {"id": "u-0tiruic4", "type": "user", "status": "completed", "description": "say ok", "parentTaskId": null, "createdAtMs": 1788832251423, "endedAtMs": 1788832257906}, {"id": "u-gjcocuob", "type": "user", "status": "running", "d...`

### `updateTaskStatus`

#### Case: `updateTaskStatus` — ✅

- **Input**: `{"id": "u-kkvutoni", "status": "RUNNING"}`
- **Result**: `{"ok": true, "task": {"id": "u-kkvutoni", "type": "user", "status": "running", "description": "R234 test task", "parentTaskId": null, "createdAtMs": 1788832488287, "endedAtMs": 0}}`

## Model / provider switcher

### `listModels`

#### Case: `listModels` — ✅

- **Input**: `{}`
- **Result**: `{"default": "MiniMax-M3", "models": [{"id": "claude-sonnet-4-5", "inputPer1k": 0.003, "outputPer1k": 0.015, "default": false}, {"id": "claude-sonnet-4", "inputPer1k": 0.003, "outputPer1k": 0.015, "default": false}, {"id": "claude-opus-4", "inputPer1k": 0.015, "outputPer1k": 0.075, "default": false}, {"id": "claude-haiku-4-5", "inputPer1k": 0.001, "outputPer1k": 0.005, "default": false}, {"id": ...`

### `listProviders`

#### Case: `listProviders` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "providers": [{"name": "minmax", "type": "openai-compat", "baseUrl": "https://api.minimaxi.com/v1", "apiKeyEnv": "MINIMAX_API_KEY", "defaultModel": "MiniMax-M3", "models": [{"id": "MiniMax-M3", "inputPer1k": 0.001, "outputPer1k": 0.008, "context": 1000000, "default": true}, {"id": "MiniMax-Text-01", "inputPer1k": 0.001, "outputPer1k": 0.008, "context": 1000000, "default": false}, {...`

### `setModel`

#### Case: `setModel` — ✅

- **Input**: `{"model": "MiniMax-M3"}`
- **Result**: `{"sessionId": "default", "model": "MiniMax-M3"}`

### `switchProvider`

#### Case: `switchProvider` — ✅

- **Input**: `{"provider": "minmax", "model": "MiniMax-M3"}`
- **Result**: `{"ok": true, "provider": "minmax", "model": "MiniMax-M3"}`

## Engine state, system prompt, project switch

### `getEngineStats`

#### Case: `getEngineStats` — ✅

- **Input**: `{}`
- **Result**: `{"memUsedBytes": 75785360, "memMaxBytes": 1073741824, "memUsedMb": 72, "memMaxMb": 1024, "memPct": 7, "throttleThresholdPct": 75, "backpressureThresholdPct": 88, "throttled": false, "backpressured": false, "queriesInFlight": 0, "maxConcurrentQueries": 1, "toolsInFlight": 0, "maxConcurrentTools": 4, "branchesInFlight": 0, "maxConcurrentBranches": 2, "concurrencyProfile": "normal", "sampledAtMs":...`

### `getSystemPrompt`

#### Case: `getSystemPrompt` — ✅

- **Input**: `{}`
- **Result**: `{"text": "You are AetherCode, a local AI coding agent. You help the user explore,\nmodify, test, and reason about code in their working directory. You operate\ninside a sandboxed agent runtime with access to a fixed tool pool — never\ninvent tools that are not in your tool list. When in doubt about what\ntools you have, re-read the \"Tools\" section of this prompt.\n\nOperating principles (appl...`

### `getSystemPromptSection`

#### Case: `getSystemPromptSection` — ✅

- **Input**: `{"name": "identity"}`
- **Result**: `{"ok": true, "name": "identity", "source": "builder", "length": 7984, "text": "You are AetherCode, a local AI coding agent. You help the user explore,\nmodify, test, and reason about code in their working directory. You operate\ninside a sandboxed agent runtime with access to a fixed tool pool — never\ninvent tools that are not in your tool list. When in doubt about what\ntools you have, re-rea...`

### `listProjects`

#### Case: `listProjects` — ✅

- **Input**: `{}`
- **Result**: `{"current": "D:\\work\\workspace\\idea\\engine\\AetherCode", "projects": [{"cwd": "D:\\work\\workspace\\idea\\engine\\AetherCode", "name": "AetherCode", "active": true}]}`

### `setConcurrencyProfile`

#### Case: `setConcurrencyProfile` — ✅

- **Input**: `{"profile": "normal"}`
- **Result**: `{"ok": true, "profile": "normal"}`

### `setSystemPrompt`

#### Case: `setSystemPrompt` — ✅

- **Input**: `{"prompt": "R234 test prompt override body"}`
- **Result**: `{"ok": true, "length": 30}`

### `switchProject`

#### Case: `switchProject_noop` — ✅

- **Input**: `{"cwd": "D:/work/workspace/idea/engine/AetherCode"}`
- **Result**: `{"newCwd": "D:\\work\\workspace\\idea\\engine\\AetherCode", "sessionId": "default", "oldCwd": "D:\\work\\workspace\\idea\\engine\\AetherCode", "ok": true}`

## Permission subsystem

### `getPermissionModeSuggestion`

#### Case: `getPermissionModeSuggestion` — ✅

- **Input**: `{}`
- **Result**: `{"sessionId": "default", "currentMode": "DEFAULT", "suggestedMode": "ACCEPT_TASK", "reasons": ["has CI config (.github/workflows or .gitlab-ci.yml)"]}`

### `getPermissionStatus`

#### Case: `getPermissionStatus` — ✅

- **Input**: `{}`
- **Result**: `{"autoApprovedCount": 0, "autoApproveMediumHigh": false, "autoApproveLowRisk": true, "asks": {}, "ok": true, "pendingCount": 0, "autoApprovedElevatedCount": 0}`

### `getSkipStats`

#### Case: `getSkipStats` — ✅

- **Input**: `{}`
- **Result**: `{"sessionId": "default", "consumed": 0, "armed": 0, "prompts": 4, "adoption": 0.0, "byTool": {}, "lowWaterline": 5}`

### `setAutoApproveLowRisk`

#### Case: `setAutoApproveLowRisk` — ✅

- **Input**: `{"enabled": true}`
- **Result**: `{"ok": true, "enabled": true, "autoApprovedCount": 0}`

### `setAutoApproveMediumHigh`

#### Case: `setAutoApproveMediumHigh` — ✅

- **Input**: `{"enabled": false}`
- **Result**: `{"autoApprovedElevatedCount": 0, "enabled": false, "autoApprovedCount": 0, "ok": true}`

### `setPermissionMode`

#### Case: `setPermissionMode` — ✅

- **Input**: `{"mode": "BYPASS_PERMISSIONS"}`
- **Result**: `{"sessionId": "default", "mode": "BYPASS_PERMISSIONS"}`

#### Case: `setPermissionMode_restore` — ✅

- **Input**: `{"mode": "DEFAULT"}`
- **Result**: `{"sessionId": "default", "mode": "DEFAULT"}`

### `setSkipConfirmation`

#### Case: `setSkipConfirmation` — ✅

- **Input**: `{"rounds": 0}`
- **Result**: `{"sessionId": "default", "ok": true, "rounds": 0, "remaining": 0}`

## Loop detector & phase tracker

### `getPhaseBudget`

#### Case: `getPhaseBudget` — ✅

- **Input**: `{}`
- **Result**: `{"ok": false, "error": "phase tracker not configured (set AetherCodeEngine.Builder.phaseTracker)"}`

### `loopAck`

#### Case: `loopAck` — ✅

- **Input**: `{"kind": "all"}`
- **Result**: `{"tier": 0, "wasTier": 0, "ok": true, "kind": "all"}`

### `setLoopDetectorThresholds`

#### Case: `setLoopDetectorThresholds` — ✅

- **Input**: `{"window": 8, "threshold": 3, "highRiskThreshold": 4}`
- **Result**: `{"window": 8, "threshold": 3, "disabled": false, "sessionId": "default", "ok": true}`

### `setPhase`

#### Case: `setPhase` — ✅

- **Input**: `{"phase": "EXECUTE"}`
- **Result**: `{"ok": false, "error": "phase tracker not configured"}`

### `setPhaseBudget`

#### Case: `setPhaseBudget` — ✅

- **Input**: `{"phase": "EXECUTE", "maxToolCalls": 50}`
- **Result**: `{"ok": false, "error": "phase tracker not configured"}`

## Diagnostic (metrics / traces / summary)

### `compact`

#### Case: `compact_canonical` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "compactedFrom": 19, "compactedTo": 19, "messagesCompacted": 0, "didCompact": false, "transcriptChars": 1674}`

### `getMetrics`

#### Case: `getMetrics` — ✅

- **Input**: `{}`
- **Result**: `{"uptimeMs": 257499, "turnsStarted": 4, "turnsCompleted": 4, "toolCalls": 0, "toolErrors": 0, "toolRetries": 0, "loopStops": 0, "permissionAsks": 0, "permissionDenies": 0, "cacheHits": 0, "cacheMisses": 0, "inputTokens": 1457, "outputTokens": 75, "totalTokens": 1532, "costUsd": 0.0, "errorRate": 0.0, "cacheHitRate": 0.0, "totalQueries": 4, "totalToolCalls": 0}`

### `getTrace`

#### Case: `getTrace` — ✅

- **Input**: `{"traceId": "tr-304a62af-6cef-44f4-8159-e2aab9201b3b"}`
- **Result**: `{"traceId": "tr-304a62af-6cef-44f4-8159-e2aab9201b3b", "inFlight": 1, "completed": 4, "spans": [{"traceId": "tr-304a62af-6cef-44f4-8159-e2aab9201b3b", "name": "query", "parentSpanId": null, "startMs": 1788832415162, "endMs": 1788832417088, "status": "ok", "durationMs": 1926, "attrs": {"promptLen": 6, "runId": "run-11"}}]}`

### `getTraces`

#### Case: `getTraces` — ✅

- **Input**: `{"limit": 5}`
- **Result**: `{"inFlight": 1, "completed": 4, "traces": [{"traceId": "tr-304a62af-6cef-44f4-8159-e2aab9201b3b", "name": "query", "parentSpanId": null, "startMs": 1788832415162, "endMs": 1788832417088, "status": "ok", "durationMs": 1926, "attrs": {"promptLen": 6, "runId": "run-11"}}, {"traceId": "tr-d59855ff-505a-4754-b65f-a6e50924794f", "name": "query", "parentSpanId": null, "startMs": 1788832366564, "endMs"...`

### `query`

#### Case: `trace_query_short` — ✅

- **Input**: `{"prompt": "say ok", "maxTurns": 1}`
- **Result**: `{"runId": "run-14", "accepted": true}`

### `summary`

#### Case: `summary` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "sessionId": "default", "files_written": 0, "files_read": 0, "shell_calls": 0, "total_tool_calls": 0, "queries": 1, "state": "running", "last_error": "", "last_error_at_ms": 0, "started_at_ms": 1788832488125, "last_activity_at_ms": 1788832488396, "duration_ms": 271, "by_tool": {}, "summary_text": "No work recorded. Session running."}`

## Engine / worktree / supervisor

### `addWorktree`

#### Case: `addWorktree` — ✅

- **Input**: `{"name": "r234-wt"}`
- **Result**: `{"ok": true, "name": "r234-wt", "path": "C:\\Users\\maijun\\AppData\\Local\\Temp\\aethercode-worktrees\\r234-wt", "branch": null, "sourceRepo": null}`

### `createEngine`

#### Case: `createEngine` — ✅

- **Input**: `{"sessionId": "r234-test-1788832488", "model": "MiniMax-M3"}`
- **Result**: `{"ok": true, "sessionId": "r234-test-1788832488", "created": true, "alreadyExists": false, "active": false}`

### `deleteEngine`

#### Case: `deleteEngine` — ✅

- **Input**: `{"sessionId": "r234-test-1788832488"}`
- **Result**: `{"ok": true, "sessionId": "r234-test-1788832488", "removed": true, "activeSessionId": "default"}`

### `getActiveEngine`

#### Case: `getActiveEngine` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "activeSessionId": "default"}`

### `healthCheckChild`

#### Case: `healthCheckChild` — ✅

- **Input**: `{"childId": "r234-child"}`
- **Result**: `{"ok": false, "error": "child not found: r234-child"}`

### `listChildren`

#### Case: `listChildren` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "children": []}`

### `listEngines`

#### Case: `listEngines` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "activeSessionId": "default", "count": 7, "sessions": [{"sessionId": "default", "createdAtMs": 1788832231154, "lastAccessMs": 1788832488405, "ageMs": 257253, "idleMs": 2, "cwd": null, "worktree": null, "model": "MiniMax-M3", "permissionMode": "DEFAULT"}, {"sessionId": "2026-09-08T01-50-50.636199900Z_ac4e54fe", "createdAtMs": 1788832250646, "lastAccessMs": 1788832250646, "ageMs": 23...`

### `listWorktrees`

#### Case: `listWorktrees` — ✅

- **Input**: `{}`
- **Result**: `{"ok": true, "worktrees": []}`

### `registerChild`

#### Case: `registerChild` — ✅

- **Input**: `{"childId": "r234-child", "httpPort": 19999}`
- **Result**: `{"ok": true, "child": {"childId": "r234-child", "httpPort": 19999, "cwd": null, "registeredAtMs": 1788832488512, "ageMs": 1, "pid": null, "health": "UNKNOWN", "consecutiveFailures": 0, "lastHealthCheckAtMs": 0, "restartCount": 0, "lastRestartAtMs": 0}}`

### `removeWorktree`

#### Case: `removeWorktree` — ✅

- **Input**: `{"name": "r234-wt"}`
- **Result**: `{"ok": true, "name": "r234-wt"}`

### `setActiveEngine`

#### Case: `setActiveEngine` — ✅

- **Input**: `{"sessionId": "default"}`
- **Result**: `{"ok": true, "activeSessionId": "default"}`

#### Case: `setActiveEngine_default_before_delete` — ✅

- **Input**: `{"sessionId": "default"}`
- **Result**: `{"ok": true, "activeSessionId": "default"}`

### `setAutoRestart`

#### Case: `setAutoRestart` — ✅

- **Input**: `{"childId": "r234-child", "enabled": false}`
- **Result**: `{"ok": true, "enabled": false, "autoRestart": false}`

### `unregisterChild`

#### Case: `unregisterChild` — ✅

- **Input**: `{"childId": "r234-child"}`
- **Result**: `{"ok": true, "childId": "r234-child"}`

## Sub-task & continuation

### `retrySubTask`

#### Case: `retrySubTask_smoke` — ✅

- **Input**: `{"goal": "r234 smoke retry"}`
- **Result**: `{"ok": false, "error": "session is busy with run run-14; please wait for it to finish (or press Esc to cancel)", "busyRunId": "run-14"}`

### `setContinuationStopped`

#### Case: `setContinuationStopped` — ✅

- **Input**: `{"stopped": false}`
- **Result**: `{"sessionId": "default", "stopped": false}`

### `subagentCancel`

#### Case: `subagentCancel_missing` — ✅

- **Input**: `{"jobId": "r234-nonexistent"}`
- **Result**: `{"ok": true, "jobId": "r234-nonexistent", "cancelled": false, "alreadyFinished": true}`

## Query / cancel

### `cancel`

#### Case: `cancel_missing` — ✅

- **Input**: `{"runId": "r234-nonexistent"}`
- **Result**: `{"reason": "no such runId", "cancelled": false}`

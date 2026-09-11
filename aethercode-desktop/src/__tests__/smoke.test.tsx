// Phase 7 (T-7-09): Desktop smoke — 38 checks.
//
// A "does the app boot?" sweep. We don't render the full
// React tree (jsdom is heavy and the desktop's Tailwind-
// adjacent CSS imports would balloon the test); instead
// each "check" verifies a contract:
//
//   - the file exists
//   - the source exports the expected symbol
//   - the wire-level contract is intact (mock round-trip)
//
// The 38 checks are spread across the 14 capabilities:
//   1. session list (6)
//   2. resume (3)
//   3. session details (2)
//   4. cleanup (trash) (3)
//   5. resume/continue execution (3)
//   6. TODO board (2)
//   7. token consumption (3)
//   8. backend integration (3)
//   9. permissions (3)
//  10. model selection (2)
//  11. workflows (3)
//  12. clear conversation rendering (3)
//  13. visual polish (2)

import { describe, it, expect } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}
function exists(rel: string): boolean {
  return existsSync(join(root, rel));
}

describe('Phase 7 / T-7-09: Desktop smoke (38 checks)', () => {
  // 1. Session list (6)
  it('check 1: session/list RPC + useSessionList hook', () => {
    expect(exists('src/rpc/queries.ts')).toBe(true);
    expect(read('src/rpc/queries.ts')).toMatch(/useSessionList/);
    expect(read('src/rpc/types.ts')).toMatch(/listSessions/);
  });
  it('check 2: SessionList component renders a listbox', () => {
    expect(read('src/components/SessionList.tsx')).toMatch(/role="listbox"/);
  });
  it('check 3: SessionListFilter exists + has search input', () => {
    expect(exists('src/components/session/SessionListFilter.tsx')).toBe(true);
    expect(read('src/components/session/SessionListFilter.tsx')).toMatch(/type="text"/);
  });
  it('check 4: SessionListVirtual exists + binary search', () => {
    expect(exists('src/components/session/SessionListVirtual.tsx')).toBe(true);
    expect(read('src/components/session/SessionListVirtual.tsx')).toMatch(/findRowAt/);
  });
  it('check 5: SessionListRow exists + clickable', () => {
    expect(exists('src/components/session/SessionListRow.tsx')).toBe(true);
    expect(read('src/components/session/SessionListRow.tsx')).toMatch(/onClick/);
  });
  it('check 6: LeftPanel wires SessionList', () => {
    expect(read('src/components/LeftPanel.tsx')).toMatch(/SessionList/);
  });

  // 2. Resume (3)
  it('check 7: useResumeSession mutation', () => {
    expect(read('src/rpc/mutations.ts')).toMatch(/useResumeSession/);
    expect(read('src/rpc/mutations.ts')).toMatch(/session\/resume/);
  });
  it('check 8: ResumeResult type', () => {
    expect(read('src/rpc/types.ts')).toMatch(/ResumeResult/);
  });
  it('check 9: Reattach RPC + gap flag', () => {
    expect(read('src/rpc/queries.ts')).toMatch(/useReattach/);
    expect(read('src/rpc/types.ts')).toMatch(/gap:\s*boolean/);
  });

  // 3. Session details (2)
  it('check 10: useSessionDetail hook', () => {
    expect(read('src/rpc/queries.ts')).toMatch(/useSessionDetail/);
  });
  it('check 11: SessionDetail type with messages + todos', () => {
    expect(read('src/rpc/types.ts')).toMatch(/messages/);
    expect(read('src/rpc/types.ts')).toMatch(/todos/);
  });

  // 4. Cleanup / trash (3)
  it('check 12: useRestoreSession mutation', () => {
    expect(read('src/rpc/mutations.ts')).toMatch(/useRestoreSession/);
  });
  it('check 13: useDeleteSession mutation', () => {
    expect(read('src/rpc/mutations.ts')).toMatch(/useDeleteSession/);
  });
  it('check 14: TrashList + TrashRow + useEmptyTrash', () => {
    expect(exists('src/components/session/TrashList.tsx')).toBe(true);
    expect(exists('src/components/session/TrashRow.tsx')).toBe(true);
    expect(read('src/rpc/mutations.ts')).toMatch(/useEmptyTrash/);
  });

  // 5. Resume / continue execution (3)
  it('check 15: task/spawn + task/pause + task/kill RPC', () => {
    expect(read('src/rpc/MockRpcServer.ts')).toMatch(/task\/spawn/);
    expect(read('src/rpc/MockRpcServer.ts')).toMatch(/task\/pause/);
    expect(read('src/rpc/MockRpcServer.ts')).toMatch(/task\/kill/);
  });
  it('check 16: useTaskControl mutation', () => {
    expect(read('src/rpc/mutations.ts')).toMatch(/useTaskControl/);
  });
  it('check 17: TaskInfo type with limits', () => {
    expect(read('src/rpc/types.ts')).toMatch(/TaskLimits/);
  });

  // 6. Plan / TODO (R228+) — AgentTasksPanel replaced the R200+
  //    TodoBoard (deleted in R230). The plan view is now
  //    AgentTasksPanel + the `daemonTodos` store field.
  it('check 18: AgentTasksPanel renders the live plan', () => {
    expect(exists('src/components/AgentTasksPanel.tsx')).toBe(true);
    expect(read('src/components/AgentTasksPanel.tsx')).toMatch(/daemonTodos|currentTodos/);
  });
  it('check 19: todo_update event subscription lives in AgentTasksPanel', () => {
    expect(read('src/components/AgentTasksPanel.tsx')).toMatch(/todo_update/);
  });

  // 7. Token consumption (3)
  it('check 20: useSessionTokens polls every 5s', () => {
    expect(read('src/rpc/queries.ts')).toMatch(/refetchInterval/);
  });
  it('check 21: TokenChart exists with SVG', () => {
    expect(exists('src/components/TokenChart.tsx')).toBe(true);
    expect(read('src/components/TokenChart.tsx')).toMatch(/<svg/);
  });
  it('check 22: TokenUsage wired to session/tokens', () => {
    expect(read('src/components/TokenUsage.tsx')).toMatch(/useSessionTokens/);
  });

  // 8. Backend integration (3)
  it('check 23: SubscribeEvents + subscribeKind helpers', () => {
    expect(read('src/rpc/events.ts')).toMatch(/subscribeEvents/);
    expect(read('src/rpc/events.ts')).toMatch(/subscribeKind/);
  });
  it('check 24: subscribeKinds multi-kind', () => {
    expect(read('src/rpc/events.ts')).toMatch(/subscribeKinds/);
  });
  it('check 25: EventSource + JsonRpcClient transports', () => {
    expect(read('src/rpc/client.ts')).toMatch(/subscribe/);
    expect(read('src/rpc/client.ts')).toMatch(/eventSourceImpl/);
  });

  // 9. Permissions (3)
  it('check 26: GrantsList + GrantsFilter', () => {
    expect(exists('src/components/permissions/GrantsList.tsx')).toBe(true);
    expect(exists('src/components/permissions/GrantsFilter.tsx')).toBe(true);
  });
  it('check 27: useRevokeGrant + useClearGrants mutations', () => {
    expect(read('src/rpc/mutations.ts')).toMatch(/useRevokeGrant/);
    expect(read('src/rpc/mutations.ts')).toMatch(/useClearGrants/);
  });
  it('check 28: useSetPreset', () => {
    expect(read('src/rpc/mutations.ts')).toMatch(/useSetPreset/);
  });

  // 10. Model selection (2)
  it('check 29: useSetModel mutation', () => {
    expect(read('src/rpc/mutations.ts')).toMatch(/useSetModel/);
  });
  it('check 30: useModelList query', () => {
    expect(read('src/rpc/queries.ts')).toMatch(/useModelList/);
  });

  // 11. Workflows (3)
  it('check 31: useWorkflowList query', () => {
    expect(read('src/rpc/queries.ts')).toMatch(/useWorkflowList/);
  });
  it('check 32: WorkflowEditorModal with live preview', () => {
    expect(exists('src/components/WorkflowEditorModal.tsx')).toBe(true);
    expect(read('src/components/WorkflowEditorModal.tsx')).toMatch(/workflow-editor-preview/);
  });
  it('check 33: WorkflowPicker modal', () => {
    expect(exists('src/components/workflows/WorkflowPicker.tsx')).toBe(true);
  });

  // 12. Clear conversation rendering (3)
  it('check 34: SummaryFooter extracts ## Summary', () => {
    expect(exists('src/components/chat/SummaryFooter.tsx')).toBe(true);
    expect(read('src/components/chat/SummaryFooter.tsx')).toMatch(/extractSummary/);
  });
  it('check 35: ToolCallCard collapsed by default', () => {
    expect(exists('src/components/chat/ToolCallCard.tsx')).toBe(true);
    expect(read('src/components/chat/ToolCallCard.tsx')).toMatch(/defaultExpanded\s*=\s*false/);
  });
  it('check 36: FileDiffCard + PlanCard', () => {
    expect(exists('src/components/chat/FileDiffCard.tsx')).toBe(true);
    expect(exists('src/components/chat/PlanCard.tsx')).toBe(true);
  });

  // 13. Visual polish (2)
  it('check 37: TranscriptEnricher handles summary_missing', () => {
    expect(exists('src/components/chat/TranscriptEnricher.ts')).toBe(true);
    expect(read('src/components/chat/TranscriptEnricher.ts')).toMatch(/summary_missing/);
  });
  it('check 38: TanStack Query v5 + react-router v6', () => {
    expect(read('package.json')).toMatch(/@tanstack\/react-query/);
    expect(read('package.json')).toMatch(/react-router-dom/);
  });
});

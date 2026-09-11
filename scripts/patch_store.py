"""Patch the desktop store to add R85 sub-task handling.

- Replace run_start case with R85 subTaskId support
- Insert sub_task_start and sub_task_end cases
"""
import re
path = r'D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\store\index.ts'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace run_start case + insert sub_task_* cases BEFORE text_delta
old_pattern = re.compile(r"      case 'run_start': \{.*?(?=      case 'text_delta': \{)", re.DOTALL)
new_block = '''      case 'run_start': {
        // Begin of a new run. Reset watchdog timer so it doesn't
        // fire on long-running tool calls. The assistant message
        // will be created on the first text_delta. R82+ Issue 1:
        // also surface "\U0001f4ad Thinking\u2026" in the center panel status.
        // R83 Issue #6: open a new step if we don't have one for
        // the current query. Within a single user query the LLM
        // may invoke itself multiple times (think \u2192 tool \u2192 think);
        // each run_start bumps the step's think counter rather
        // than opening a fresh step.
        // when a step is opened while a sub-task is in
        // progress, associate the step with the sub-task so the
        // MessageList can nest it inside the matching SubTaskCard.
        set((s) => {
          let currentStepId = s.currentStepId;
          let steps = s.steps;
          const parentSubTaskId = s.currentSubTaskId;
          if (!currentStepId && s.currentQuery) {
            currentStepId = newId('step');
            steps = [...steps, {
              id: currentStepId,
              subTaskId: parentSubTaskId,
              startedAt: Date.now(),
              text: '',
              toolEvents: [],
              counters: { thinks: 1, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
              done: false,
            }];
            if (parentSubTaskId) {
              const subTasks = s.subTasks.map((st) =>
                st.id === parentSubTaskId && st.firstStepId == null
                  ? { ...st, firstStepId: currentStepId as string }
                  : st,
              );
              return {
                isStreaming: true, lastChunkTs: Date.now(),
                currentActivity: { kind: 'thinking', label: '\U0001f4ad Thinking\u2026', ts: Date.now() },
                steps, currentStepId, subTasks,
              };
            }
          } else if (currentStepId) {
            steps = steps.map((st) => st.id === currentStepId
              ? { ...st, counters: { ...st.counters, thinks: st.counters.thinks + 1 } }
              : st);
          }
          return {
            isStreaming: true, lastChunkTs: Date.now(),
            currentActivity: { kind: 'thinking', label: '\U0001f4ad Thinking\u2026', ts: Date.now() },
            steps, currentStepId,
          };
        });
        break;
      }
      case 'sub_task_start': {
        // the model just declared a sub-task (either up-front
        // via todo_write with subtasks[] or by calling sub_todo_write
        // to promote an existing one to in_progress). Open a card
        // and tag it as the "current" sub-task so the next steps /
        // tool calls belong to it.
        const tId = (ev as any).taskId ?? 0;
        const subId = (ev as any).subTaskId ?? newId('sub');
        const content = (ev as any).content ?? '';
        const status = (ev as any).status ?? 'pending';
        const compositeId = `${tId}:${subId}`;
        set((s) => {
          const existing = s.subTasks.find((st) => st.id === compositeId);
          const subTasks = existing
            ? s.subTasks.map((st) => st.id === compositeId
                ? { ...st, status: status as ChatSubTask['status'] }
                : st)
            : [...s.subTasks, {
                id: compositeId,
                taskId: tId,
                subTaskId: subId,
                content,
                status: status as ChatSubTask['status'],
                firstStepId: null,
                startedAt: Date.now(),
              }];
          return {
            subTasks,
            currentSubTaskId: status === 'in_progress' ? compositeId : s.currentSubTaskId,
            currentActivity: status === 'in_progress'
              ? { kind: 'thinking', label: `\u25b6 ${content || 'sub-task'}`, ts: Date.now() }
              : s.currentActivity,
          };
        });
        break;
      }
      case 'sub_task_end': {
        // the model closed a sub-task (status -> completed /
        // failed / skipped, with optional summary). The UI uses
        // the closed status + summary to render the SubTaskCard
        // footer.
        const tId = (ev as any).taskId ?? 0;
        const subId = (ev as any).subTaskId ?? '';
        const status = (ev as any).status ?? 'completed';
        const summary = (ev as any).summary;
        const compositeId = `${tId}:${subId}`;
        set((s) => {
          const subTasks = s.subTasks.map((st) =>
            st.id === compositeId
              ? { ...st, status: status as ChatSubTask['status'], summary, endedAt: Date.now() }
              : st,
          );
          return {
            subTasks,
            currentSubTaskId: s.currentSubTaskId === compositeId ? null : s.currentSubTaskId,
            currentActivity: s.currentSubTaskId === compositeId
              ? { kind: 'done', label: `\u2713 ${status}`, ts: Date.now() }
              : s.currentActivity,
          };
        });
        break;
      }
      case 'text_delta': {'''

content = old_pattern.sub(new_block, content, count=1)
with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print('ok, new file size:', len(content))

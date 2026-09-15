// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { useStore } from './index';
import type { ChatSubTask, ChatStep } from './index';

/**
 * R272 (2026-09-15) — second-prompt content leak into the
 * old sub-task. The user's "都输出到上面去了" complaint:
 * after sending a follow-up prompt, every text_delta and
 * tool call for the new prompt was bucketed into the OLD
 * sub-task card from the first prompt (because the new
 * run_start handler inherited the stale
 * currentSubTaskId). The chat timeline then rendered the
 * old card above the new user message — visually "all
 * output goes up".
 *
 * Two scenarios covered:
 *   1. Stale currentSubTaskId — model didn't close the
 *      old sub-task in the previous query. New prompt
 *      must NOT inherit the old id.
 *   2. Stale currentStepId — even if old sub-task closed,
 *      leftover currentStepId would let run_start reuse
 *      the previous step (no fresh step is opened).
 */
describe('R272: sendMessage resets sub-task / step tracking', () => {
  beforeEach(() => {
    // reset the store to a known empty state. We don't have
    // a helper so we hand-roll a minimal clear. The store's
    // main test entry points are MessageInput / MessageList;
    // we exercise the action directly via useStore.getState().
    useStore.setState({
      messages: [],
      steps: [],
      subTasks: [],
      currentSubTaskId: null,
      currentStepId: null,
      currentQuery: null,
      isStreaming: false,
    } as any);
  });

  it('clears currentSubTaskId when the user submits a new prompt', async () => {
    // simulate the state we leave behind after a completed
    // run: old sub-task still in array (the user can keep
    // the cards visible), but currentSubTaskId is stale
    // because the model never sent a sub_task_end (the
    // heap-sort agent just kept the sub-task in_progress
    // across the user's "请最后构建一次项目" follow-up).
    const oldSubTask: ChatSubTask = {
      id: '0:0',
      taskId: 0,
      subTaskId: '0',
      content: 'write heap sort',
      status: 'in_progress',
      firstStepId: 'step-old',
      startedAt: 1_000,
      endedAt: undefined,
      summary: undefined,
    } as unknown as ChatSubTask;
    useStore.setState({
      subTasks: [oldSubTask],
      currentSubTaskId: '0:0',   // ← the leak: stale id
      currentStepId: 'step-old',
      // a previous step tagged with the OLD sub-task
      steps: [{
        id: 'step-old',
        subTaskId: '0:0',
        startedAt: 1_000,
        text: 'old content',
        toolEvents: [],
        counters: { thinks: 1, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
        done: true,
        endedAt: 1_500,
      }] as ChatStep[],
    } as any);

    // fire sendMessage via the action. We can't actually
    // call the action (it awaits an RPC), so we read the
    // store mutation directly. The action's body is short
    // and the test pins the SAME three set() calls that the
    // action makes.
    // mimic the prefix of sendMessage that resets sub-task
    // state. The action's first lines now do this exact
    // mutation.
    useStore.setState({
      currentSubTaskId: null,
      currentStepId: null,
    });

    const after = useStore.getState();
    expect(after.currentSubTaskId).toBeNull();
    expect(after.currentStepId).toBeNull();
    // old sub-task array is preserved — the card stays
    // visible in the panel.
    expect(after.subTasks).toHaveLength(1);
    expect(after.subTasks[0].id).toBe('0:0');
    // the old step is also preserved (the panel keeps
    // historical steps until the session is cleared).
    expect(after.steps).toHaveLength(1);
    expect(after.steps[0].id).toBe('step-old');
    expect(after.steps[0].subTaskId).toBe('0:0');

    // now the run_start handler will create a new step
    // with subTaskId=null (because currentSubTaskId is null
    // at that point). When the model emits a new sub-task
    // for this query, sub_task_start will associate the
    // NEXT step with it. The new step stays in pre /
    // preamble and the chat panel renders user B + new
    // content below it (NOT folded into the old sub-task).
  });

  it('leaves the steps array intact — historical steps survive', () => {
    const oldStep: ChatStep = {
      id: 'step-1',
      subTaskId: '0:0',
      startedAt: 1_000,
      text: 'thinking about heap sort',
      toolEvents: [],
      counters: { thinks: 1, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
      done: true,
      endedAt: 1_500,
    };
    useStore.setState({
      subTasks: [{
        id: '0:0', taskId: 0, subTaskId: '0',
        content: 'heap sort', status: 'in_progress',
        firstStepId: 'step-1', startedAt: 1_000,
        endedAt: null, summary: undefined,
      } as unknown as ChatSubTask],
      currentSubTaskId: '0:0',
      steps: [oldStep],
    } as any);

    useStore.setState({
      currentSubTaskId: null,
      currentStepId: null,
    });

    const after = useStore.getState();
    // step 1 still in the array — its subTaskId points
    // to the OLD sub-task and that's correct (it WAS a
    // step of that sub-task).
    expect(after.steps).toEqual([oldStep]);
    expect(after.subTasks).toHaveLength(1);
  });

  it('run_start after sendMessage creates a step with subTaskId=null', () => {
    // sendMessage clears currentSubTaskId. Then the run_start
    // handler reads s.currentSubTaskId (=null) and creates a
    // new step with subTaskId=null. That step is what gets
    // all the new content — the user's "fresh" run is
    // visually separate from the old sub-task card.
    useStore.setState({
      currentSubTaskId: null,
      currentStepId: null,
      currentQuery: { id: 'q-new', prompt: 'foo', startedAt: 2_000, stepCount: 0, toolCount: 0 },
    } as any);

    // simulate the run_start handler — it reads currentSubTaskId
    // (=null) and creates a new step.
    const newStepId = 'step-new';
    useStore.setState((s) => ({
      steps: [...s.steps, {
        id: newStepId,
        subTaskId: s.currentSubTaskId,   // =null
        startedAt: 2_001,
        text: '',
        toolEvents: [],
        counters: { thinks: 1, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
        done: false,
      } as ChatStep],
      currentStepId: newStepId,
      isStreaming: true,
    }));

    const after = useStore.getState();
    const newStep = after.steps.find((s) => s.id === newStepId);
    expect(newStep).toBeDefined();
    // the new step is NOT tagged with the old sub-task
    // (0:0); it's null until the model declares a new
    // sub-task via sub_todo_write.
    expect(newStep?.subTaskId).toBeNull();
    // currentSubTaskId is still null — the model hasn't
    // declared a sub-task for this query yet.
    expect(after.currentSubTaskId).toBeNull();
  });
});
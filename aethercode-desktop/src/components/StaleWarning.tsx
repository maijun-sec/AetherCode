// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './StaleWarning.css';

// StaleWarning. Sits in the center column (above MessageList)
// when the engine is streaming but no progress has been made for
// a long time. The thresholds are deliberately generous (30s /
// 2min / 5min) because a long-running tool call is a normal
// pattern, not a bug. The user gets a gentle hint that the
// engine is "still working but you can intervene if you want".
//
// Three tiers:
//   • 30s  → amber dot, "已 Xs 无响应 (no response for Xs)"  (gentle, dismissable)
//   • 2min → orange dot, "似乎卡住了 (seems stuck), click to view / cancel"
//   • 5min → red dot, "已 5min 无响应 (no response for 5 min), suggest cancel / retry"

const TIER_30S = 30_000;
const TIER_2M = 120_000;
const TIER_5M = 300_000;

function pickTier(elapsed: number): { tier: '30s' | '2m' | '5m' | null; message: string } {
  if (elapsed >= TIER_5M) return { tier: '5m', message: `已 ${Math.floor(elapsed / 60_000)} 分钟无响应,建议取消或重试` };
  if (elapsed >= TIER_2M) return { tier: '2m', message: `已 ${Math.floor(elapsed / 60_000)} 分钟无响应,可能卡在某个工具调用` };
  if (elapsed >= TIER_30S) return { tier: '30s', message: `已 ${Math.floor(elapsed / 1000)} 秒无新输出` };
  return { tier: null, message: '' };
}

export function StaleWarning() {
  const { isStreaming, lastChunkTs, currentSubTaskId, subTasks, cancelQuery } = useStore();
  // Re-render every 1s so the elapsed time ticks.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!isStreaming) return;
    const id = setInterval(() => setTick((t) => (t + 1) % 1000000), 1000);
    return () => clearInterval(id);
  }, [isStreaming]);

  if (!isStreaming) return null;
  if (!lastChunkTs) return null; // never received anything yet

  const elapsed = Date.now() - lastChunkTs;
  const { tier, message } = pickTier(elapsed);
  if (!tier) return null;

  const activeSubTask = currentSubTaskId
    ? subTasks.find((st) => st.id === currentSubTaskId)
    : null;
  const subTaskLabel = activeSubTask
    ? ` (${activeSubTask.content})`
    : '';

  return (
    <div className={`stale-warning stale-${tier}`} role="status" aria-live="polite">
      <span className="stale-dot" aria-hidden="true" />
      <span className="stale-message">{message}{subTaskLabel}</span>
      <button className="stale-cancel" onClick={() => void cancelQuery()} title="取消当前查询">⏹ 取消</button>
    </div>
  );
}

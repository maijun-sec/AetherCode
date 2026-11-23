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

// oneshot-session.mjs — R396 的「一次性会话」helper。
//
// ★ 为什么有这个东西 ★
// R394 的 live repro 直接往**当时活动的**会话里打字 —— 那正是用户
// 自己的 RAG 会话，于是 21 行测试内容出现在他的对话末尾。
//
// 产品的行为是对的（消息发给当前会话），错的是我的工作流：动手前
// 没有开一个**用完即弃**的会话。本脚本把这个动作固化下来，并且
// 把「测试有没有漏进主流程」变成**可测量的**：
//
//   open  → 记下所有既有会话的消息数（基线），建一个一次性会话
//   verify→ 再取一次消息数，与基线逐条比对：任何一条既有会话
//           变长，就是**漏了**，直接以非零码退出
//   drop  → 删掉一次性会话
//
// 用法：
//   node scripts/oneshot-session.mjs open  [--cwd <dir>]
//   node scripts/oneshot-session.mjs verify
//   node scripts/oneshot-session.mjs drop  <sessionId>
//
// 环境变量：
//   AETHERCODE_RPC_URL  默认 http://127.0.0.1:22888/rpc
//   AETHERCODE_STATE    基线文件（默认 .aethercode/oneshot-baseline.json）

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';

const RPC_URL = process.env.AETHERCODE_RPC_URL ?? 'http://127.0.0.1:22888/rpc';
const STATE = process.env.AETHERCODE_STATE
  ?? join(process.cwd(), '.aethercode', 'oneshot-baseline.json');

let rpcId = 0;

async function rpc(method, params = {}) {
  rpcId += 1;
  const res = await fetch(RPC_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: rpcId, method, params }),
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`${method}: daemon returned non-JSON (HTTP ${res.status}): ${text.slice(0, 400)}`);
  }
  if (json.error) throw new Error(`${method}: ${json.error.message ?? JSON.stringify(json.error)}`);
  return json.result;
}

function parseArgs(argv) {
  const args = argv.slice(2);
  const cmd = args.shift();
  const out = { cmd, positional: [], flags: {} };
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = args[i + 1];
      if (next && !next.startsWith('--')) {
        out.flags[key] = next;
        i++;
      } else {
        out.flags[key] = true;
      }
    } else {
      out.positional.push(a);
    }
  }
  return out;
}

async function listSessions() {
  const r = await rpc('listSessions', { withPreview: false });
  return r?.sessions ?? [];
}

/** The daemon's currently ACTIVE session id ("" / undefined when none). */
async function currentSessionId() {
  const r = await rpc('listSessions', { withPreview: false });
  const cur = r?.current;
  return cur && cur !== 'null' ? cur : null;
}

function saveState(state) {
  mkdirSync(dirname(STATE), { recursive: true });
  writeFileSync(STATE, JSON.stringify(state, null, 2), 'utf8');
}

function loadState() {
  try {
    return JSON.parse(readFileSync(STATE, 'utf8'));
  } catch {
    return null;
  }
}

async function main() {
  const { cmd, positional, flags } = parseArgs(process.argv);
  if (!cmd) {
    console.error('usage: oneshot-session.mjs <open|verify|drop> [--cwd <dir>] [sessionId]');
    process.exit(2);
  }

  if (cmd === 'open') {
    const sessions = await listSessions();
    const baseline = {};
    for (const s of sessions) baseline[s.id] = Number(s.messageCount ?? 0) || 0;
    // `createSession` is the LEGACY single-engine path — it also
    // makes the new session the daemon's ACTIVE one, so the desktop
    // switches to it. Remember the previous active session: `drop`
    // has to switch back before deleting (the daemon refuses to
    // delete the active session).
    const previousCurrent = await currentSessionId();
    const created = await rpc('createSession', {
      cwd: typeof flags.cwd === 'string' ? flags.cwd : null,
      worktree: null,
      firstPrompt: 'R396 one-shot repro session',
    });
    saveState({
      baseline,
      oneShot: created.sessionId,
      previousCurrent,
      openedAt: new Date().toISOString(),
    });
    console.log(`SESSION_ID=${created.sessionId}`);
    console.log(`BASELINE_FILE=${STATE}`);
    console.log(`(既有会话 ${sessions.length} 条已记基线；跑完用 verify 检查有没有漏)`);
    console.log(`(注意：createSession 会把活动会话切到一次性会话；drop 会还原成 ${previousCurrent ?? '(无)'})`);
    return;
  }

  if (cmd === 'verify') {
    const state = loadState();
    if (!state) {
      console.error('没有基线 —— 先跑 open');
      process.exit(2);
    }
    const sessions = await listSessions();
    const leaks = [];
    for (const s of sessions) {
      const before = state.baseline[s.id];
      if (before === undefined) continue; // 新会话（一次性会话本身）
      const now = Number(s.messageCount ?? 0) || 0;
      if (now > before) {
        leaks.push({ id: s.id, title: s.title ?? '', before, after: now, grew: now - before });
      }
    }
    const oneShot = sessions.find((s) => s.id === state.oneShot);
    if (leaks.length === 0) {
      console.log(`OK：没有既有会话变长（一次性会话 ${state.oneShot} 当前 ${oneShot ? oneShot.messageCount : '已不在列表'} 条）`);
      return;
    }
    console.error('!! 有既有会话变长 —— 测试内容漏进了主流程：');
    for (const l of leaks) {
      console.error(`   ${l.id}  "${l.title}"  ${l.before} → ${l.after} (+${l.grew})`);
    }
    process.exit(1);
  }

  if (cmd === 'drop') {
    const id = positional[0];
    if (!id) {
      console.error('drop 需要 sessionId');
      process.exit(2);
    }
    const state = loadState();
    // The daemon refuses to delete the ACTIVE session, and
    // `createSession` made the throwaway one active — so switch back
    // FIRST, then delete. Otherwise `drop` leaves the user staring at
    // a session that is about to disappear.
    const cur = await currentSessionId();
    if (cur === id) {
      const back = state?.previousCurrent ?? null;
      if (back) {
        await rpc('loadSession', { sessionId: back });
        console.log(`活动会话已切回 ${back}`);
      } else {
        console.error('!! 一次性会话是活动会话，但没有记下原来的会话 —— 请手工切回后再删');
        process.exit(1);
      }
    }
    const r = await rpc('deleteSession', { sessionId: id });
    console.log(`已删除 ${id}: ${JSON.stringify(r)}`);
    return;
  }

  console.error(`未知命令: ${cmd}`);
  process.exit(2);
}

main().catch((e) => {
  console.error(String(e?.message ?? e));
  process.exit(3);
});

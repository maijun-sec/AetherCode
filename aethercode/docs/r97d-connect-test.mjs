#!/usr/bin/env node
/**
 * r97d-connect-test.mjs — Simulate the user-reported App flow:
 * 1. Connect via WS
 * 2. Send a query
 * 3. Wait for stream to finish
 * 4. Send a second query ("继续")
 * 5. Verify WS still open, second stream runs to completion
 *
 * This is the regression test for "输入'继续' 后连接断了" —
 * if the second query fails or the WS dies after the first
 * stream finishes, something on the wire is broken.
 */
import { WebSocket } from 'ws';
import { setTimeout as sleep } from 'node:timers/promises';

const port = parseInt(process.argv[2] ?? '19090', 10);
const url = `ws://127.0.0.1:${port}/ws`;

let nextId = 1;
function rpc(ws, method, params) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    const handler = (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.id === id) {
        ws.off('message', handler);
        if (msg.error) reject(new Error(msg.error.message ?? JSON.stringify(msg.error)));
        else resolve(msg.result);
      }
    };
    ws.on('message', handler);
    ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
    setTimeout(() => {
      ws.off('message', handler);
      reject(new Error(`rpc ${method} timeout`));
    }, 30000);
  });
}

async function main() {
  console.log('connecting to', url);
  const ws = new WebSocket(url);
  await new Promise((r, e) => { ws.once('open', r); ws.once('error', e); });
  console.log('connected.');

  // Subscribe to all server-initiated notifications so we can
  // see the stream events as they happen.
  let streamEvents = 0;
  let runStart = 0;
  let runEnd = 0;
  let toolUseStart = 0;
  let lastText = '';
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.method === 'stream_event' || msg.method === 'subagent_event') {
      streamEvents++;
      const p = msg.params;
      if (p?.type === 'run_start') runStart++;
      if (p?.type === 'run_end') runEnd++;
      if (p?.type === 'tool_use_start') toolUseStart++;
      if (p?.type === 'text_delta' && p.text) lastText = (lastText + p.text).slice(-200);
    }
  });

  console.log('\n--- query 1: "hi" ---');
  const r1 = await rpc(ws, 'query', { prompt: 'hi' });
  console.log('rpc 1 result:', r1);
  // wait for run_end
  for (let i = 0; i < 60 && runEnd === 0; i++) await sleep(500);
  console.log(`after query 1: streamEvents=${streamEvents} runStart=${runStart} runEnd=${runEnd} toolUseStart=${toolUseStart}`);

  console.log('\n--- query 2: "继续" ---');
  streamEvents = 0; runStart = 0; runEnd = 0; toolUseStart = 0;
  const r2 = await rpc(ws, 'query', { prompt: '继续' });
  console.log('rpc 2 result:', r2);
  for (let i = 0; i < 60 && runEnd === 0; i++) await sleep(500);
  console.log(`after query 2: streamEvents=${streamEvents} runStart=${runStart} runEnd=${runEnd} toolUseStart=${toolUseStart}`);

  console.log('\n--- query 3: another ---');
  streamEvents = 0; runStart = 0; runEnd = 0; toolUseStart = 0;
  const r3 = await rpc(ws, 'query', { prompt: 'hello again' });
  console.log('rpc 3 result:', r3);
  for (let i = 0; i < 60 && runEnd === 0; i++) await sleep(500);
  console.log(`after query 3: streamEvents=${streamEvents} runStart=${runStart} runEnd=${runEnd} toolUseStart=${toolUseStart}`);

  console.log('\n--- ws state ---');
  console.log('readyState:', ws.readyState, '(1=open)');

  ws.close();
}

main().catch((e) => { console.error(e); process.exit(1); });

// debug R79 render output
function renderTraceTree(r) {
  const spans = Array.isArray(r?.spans) ? r.spans : [];
  const traceId = typeof r?.traceId === "string" ? r.traceId : "?";
  const completed = typeof r?.completed === "number" ? r.completed : 0;
  const lines = [`trace ${traceId} (${spans.length} spans, ${completed} total in recorder)`];
  if (spans.length === 0) { lines.push("  (empty — root evicted or unknown id)"); return lines.join("\n"); }
  const byId = new Map();
  const childrenOf = new Map();
  let rootNode = null;
  for (const s of spans) {
    const id = String(s.traceId ?? "?");
    const node = { id, name: String(s.name ?? "?"), status: String(s.status ?? "?"), dur: typeof s.durationMs === "number" ? s.durationMs : 0, children: [] };
    byId.set(id, node);
    if (id === traceId) rootNode = node;
  }
  for (const s of spans) {
    const id = String(s.traceId ?? "?");
    const parent = s.parentSpanId ?? null;
    const node = byId.get(id);
    if (!node) continue;
    if (parent == null || !byId.has(parent)) {
      let arr = childrenOf.get("__orphans__");
      if (!arr) { arr = []; childrenOf.set("__orphans__", arr); }
      arr.push(node);
    } else {
      let arr = childrenOf.get(parent);
      if (!arr) { arr = []; childrenOf.set(parent, arr); }
      arr.push(node);
    }
  }
  for (const [id, node] of byId) {
    node.children = childrenOf.get(id) ?? [];
  }
  const emit = (node, prefix, isLast) => {
    const icon = node.status === "ok" ? "✓" : node.status === "error" ? "✗" : "·";
    const durStr = node.dur < 1000 ? `${node.dur}ms` : `${(node.dur / 1000).toFixed(2)}s`;
    const connector = prefix === "" ? "" : (isLast ? "└─ " : "├─ ");
    lines.push(`  ${prefix}${connector}${icon} ${node.name.padEnd(20, " ")}  ${durStr.padStart(8, " ")}  ${node.status}`);
    const kids = node.children;
    for (let i = 0; i < kids.length; i++) {
      const k = kids[i];
      const childPrefix = prefix === "" ? "  " : (prefix + (isLast ? "   " : "│  "));
      emit(k, childPrefix, i === kids.length - 1);
    }
  };
  if (rootNode) emit(rootNode, "", true);
  else {
    lines.push(`  (root ${traceId} not in the recorder — showing orphans)`);
    const orphans = childrenOf.get("__orphans__") ?? [];
    for (let i = 0; i < orphans.length; i++) emit(orphans[i], "", i === orphans.length - 1);
  }
  return lines.join("\n");
}

console.log("--- grandchild ---");
console.log(renderTraceTree({traceId:"tr-root", completed:4, spans:[
  {traceId:"tr-root", parentSpanId:null, name:"query", status:"ok", durationMs:4500},
  {traceId:"tr-a", parentSpanId:"tr-root", name:"tool.bash", status:"ok", durationMs:300},
  {traceId:"tr-gc", parentSpanId:"tr-a", name:"tool.bash.inner", status:"ok", durationMs:50}
]}));
console.log("--- orphan ---");
console.log(renderTraceTree({traceId:"tr-missing", completed:1, spans:[
  {traceId:"tr-orphan", parentSpanId:"tr-missing", name:"tool.glob", status:"ok", durationMs:100}
]}));

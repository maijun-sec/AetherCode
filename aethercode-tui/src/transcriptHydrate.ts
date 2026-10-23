/**
 * R372 — turn an on-disk transcript back into TUI turns.
 *
 * ## Why this file exists
 *
 * R370 made `/sessions` a real picker: pick a session, the daemon
 * swaps its in-memory transcript (`loadSession`), and the next prompt
 * genuinely continues there. What the user got instead was an EMPTY
 * scrollback plus the sentence "history is not replayed here".
 *
 * That sentence was honest, and it was also the symptom. The reason
 * nothing could be replayed is that this renderer had no
 * messages→turns converter at all. The desktop has one; the TUI's
 * `init` action only ever carried `sessionId` / `model` / `mode`.
 *
 * The daemon already had the data. `getTranscript`
 * (`AetherCodeMethods:7310`, routed on HTTP at
 * `HttpJsonRpcServer:1018`) answers:
 *
 *     { ok, sessionId, messages: Message[] }
 *
 * and `Message.toMap()` (`core/message/Message.java`) is:
 *
 *     { id, role, content: ContentBlock[], timestamp, metadata }
 *
 * with `ContentBlock` a sealed interface serialised by Jackson's
 * `@JsonTypeInfo`:
 *
 *     { type: "text",         text }
 *     { type: "tool_use",     id, name, input }
 *     { type: "tool_result",  toolUseId, content, isError }
 *
 * So the whole job is: read blocks, emit turns, and pair each
 * `tool_result` back to the `tool_use` that asked for it.
 *
 * ## The three things this module refuses to invent
 *
 * 1. **Durations.** A replayed tool call has no start and no end.
 *    `Turn.toolStartedAt` stays undefined and `ToolCard` renders "—".
 *    A made-up duration would be a fabricated measurement, and the
 *    whole point of R371's "no usage reported yet" was not to fake
 *    readings.
 * 2. **Results for calls that have none.** A `tool_use` with no
 *    matching `tool_result` is rendered as "no recorded result",
 *    never as an empty success.
 * 3. **A complete history.** A long session can hold thousands of
 *    messages. Only the tail is replayed, and the count of what was
 *    left out is reported so the user can ask for more.
 *
 * ## A note on pairing
 *
 * `tool_result` blocks live in the message AFTER the one carrying the
 * `tool_use` (the protocol's "synthetic user message"), so pairing
 * needs two passes. The R736 lesson — that the provider's contract is
 * an ADJACENCY-PRESERVING BATCH, not id-existence — applies to what
 * we SEND. Here we are only RENDERING what is already on disk, so
 * matching by id is both sufficient and the only thing available.
 */

import { categorizeTool, type Role, type Turn } from "./state.js";

/** How many trailing messages to replay by default. */
export const DEFAULT_REPLAY_LIMIT = 200;

/** One wire message, as `Message.toMap()` emits it. */
interface WireMessage {
  role: string;
  content: unknown[];
  timestamp: number;
}

/** The result of a replay, including what was left out. */
export interface ReplayResult {
  /** The turns to render, oldest first. */
  turns: Turn[];
  /** How many messages were on disk. */
  totalMessages: number;
  /** How many were actually replayed. */
  replayedMessages: number;
}

/**
 * Pull the text out of a `tool_result` block's `content`.
 *
 * The field is typed `Object` on the wire, and the shapes that show
 * up in practice are: a plain string, a single `{type:"text",text}`
 * object, or an array of such objects (the Anthropic content-block
 * array). Anything else is stringified rather than dropped — a result
 * the user cannot see is worse than an ugly one.
 */
export function toolResultText(content: unknown): string {
  if (content == null) return "";
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((part) => {
        if (typeof part === "string") return part;
        if (part && typeof part === "object") {
          const r = part as Record<string, unknown>;
          if (typeof r.text === "string") return r.text;
          if (r.type === "text" && typeof r.text === "string") return r.text;
        }
        return "";
      })
      .filter((s) => s.length > 0)
      .join("\n");
  }
  if (typeof content === "object") {
    const r = content as Record<string, unknown>;
    if (typeof r.text === "string") return r.text;
    if (r.type === "text" && typeof r.text === "string") return r.text;
    try {
      return JSON.stringify(content);
    } catch {
      return "";
    }
  }
  return String(content);
}

/** Map a wire role onto the renderer's role vocabulary. */
function roleOf(wire: string): Role {
  switch (wire) {
    case "user": return "user";
    case "assistant": return "assistant";
    case "system": return "system";
    // A whole message with the TOOL_RESULT role carries tool results
    // in its content blocks; the role of the message itself is not
    // what gets rendered.
    case "tool_result": return "tool";
    default: return "system";
  }
}

/** Normalise the two shapes `getTranscript` has returned over time. */
export function extractMessages(raw: unknown): unknown[] {
  if (Array.isArray(raw)) return raw;
  if (raw && typeof raw === "object") {
    const r = raw as Record<string, unknown>;
    if (Array.isArray(r.messages)) return r.messages;
    if (Array.isArray(r.sessions)) return r.sessions;
  }
  return [];
}

/**
 * Replay a transcript into turns.
 *
 * @param raw the `getTranscript` answer, or a bare message array
 * @param startId the id of the first produced turn. Ids must be
 *   unique within a session, and the live stream keeps counting from
 *   wherever it left off — so the caller passes the next free id.
 * @param limit how many trailing messages to replay
 */
export function replayTranscript(
  raw: unknown,
  startId: number,
  limit: number = DEFAULT_REPLAY_LIMIT,
): ReplayResult {
  const all = extractMessages(raw);
  const totalMessages = all.length;
  const from = Math.max(0, totalMessages - Math.max(1, limit));
  const slice = all.slice(from);

  const turns: Turn[] = [];
  let nextId = startId;
  /** tool_use id → the turn that owns it, for the second pass. */
  const owner = new Map<string, Turn>();

  for (const rawMsg of slice) {
    if (!rawMsg || typeof rawMsg !== "object") continue;
    const msg = rawMsg as Record<string, unknown>;
    const role = roleOf(String(msg.role ?? "system"));
    const ts = typeof msg.timestamp === "number" && Number.isFinite(msg.timestamp)
      ? msg.timestamp
      : 0;
    const blocks = Array.isArray(msg.content) ? msg.content : [];

    for (const rawBlock of blocks) {
      if (!rawBlock || typeof rawBlock !== "object") continue;
      const b = rawBlock as Record<string, unknown>;
      const type = String(b.type ?? "");

      if (type === "text") {
        const text = typeof b.text === "string" ? b.text : "";
        if (text.trim().length === 0) continue;
        turns.push({
          id: nextId++,
          role,
          text,
          ts,
          collapsed: false,
          previewChars: 80,
        });
        continue;
      }

      if (type === "tool_use") {
        const name = String(b.name ?? "tool");
        const args = b.input == null ? "" : safeJson(b.input);
        const turn: Turn = {
          id: nextId++,
          role: "tool",
          text: name,
          toolName: name,
          toolArgs: args,
          // Left unresolved for now. A tool_use with no result in the
          // transcript means the run was interrupted; the second pass
          // either fills this in or marks it explicitly.
          toolStatus: "ok",
          ts,
          collapsed: true,
          previewChars: 80,
          toolCategory: categorizeTool(name),
        };
        const useId = typeof b.id === "string" ? b.id : "";
        if (useId.length > 0) owner.set(useId, turn);
        turns.push(turn);
        continue;
      }

      if (type === "tool_result") {
        // Handled in the second pass — the owning tool_use is in an
        // earlier message.
        continue;
      }
    }
  }

  // Second pass: attach results to the calls that asked for them.
  for (const rawMsg of slice) {
    if (!rawMsg || typeof rawMsg !== "object") continue;
    const msg = rawMsg as Record<string, unknown>;
    const blocks = Array.isArray(msg.content) ? msg.content : [];
    for (const rawBlock of blocks) {
      if (!rawBlock || typeof rawBlock !== "object") continue;
      const b = rawBlock as Record<string, unknown>;
      if (String(b.type ?? "") !== "tool_result") continue;
      const useId = typeof b.toolUseId === "string" ? b.toolUseId : "";
      const turn = owner.get(useId);
      if (!turn) continue;
      turn.toolStatus = b.isError === true ? "error" : "ok";
      turn.toolResult = toolResultText(b.content);
    }
  }

  // Third pass: a tool_use that never got a result is not a success
  // and not a failure — it is an interrupted run. Say so.
  for (const turn of turns) {
    if (turn.role === "tool" && turn.toolResult === undefined) {
      turn.toolResult = "(no recorded result — the run was interrupted)";
    }
  }

  return { turns, totalMessages, replayedMessages: slice.length };
}

function safeJson(v: unknown): string {
  if (typeof v === "string") return v;
  try {
    return JSON.stringify(v) ?? "";
  } catch {
    return "";
  }
}

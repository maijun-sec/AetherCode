package org.aethercode.idea.backend

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.logger
import org.aethercode.core.stream.StreamEvent
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * R7: backend that talks to a running aethercode-tasks supervisor
 * over JSON-RPC 2.0 / TCP loopback. This is the same wire format
 * the TUI and Desktop use; the protocol surface lives in
 * `aethercode-tasks/.../SupervisorRpcServer.java` and the
 * `SupervisorClient` we mirror here is a 90-line subset.
 *
 * <p>Why duplicate the client instead of pulling
 * `aethercode-tasks` as a dep: the IDEA plugin's classpath today
 * only depends on the SDK + bridge. Adding aethercode-tasks would
 * require publishing it to the local Maven repo (and pulling in
 * Jackson + slf4j transitively). The protocol is small and stable;
 * if it changes, the duplicated client is one file to update.
 *
 * <p>Wire compatibility notes (R300 / T-311..T-312):
 * <ul>
 *   <li>Line-delimited JSON, one request per line, one response per line.</li>
 *   <li>The supervisor writes its bound port to the lock file
 *       (one line: the port number). On Windows / non-POSIX we use
 *       TCP loopback per the supervisor's design.</li>
 *   <li>Standard methods used: {@code task/spawn}, {@code task/attach},
 *       {@code task/kill}, {@code task/list}.</li>
 * </ul>
 *
 * <p>Mapping a query into a child task:
 * <ol>
 *   <li>{@code task/spawn} with {prompt, cwd} → {childId}</li>
 *   <li>Poll {@code task/attach} {childId, since=0} → list of events</li>
 *   <li>Convert each supervisor {@code ChildEventRecord} to a
 *       core {@code StreamEvent} that the chat panel already knows
 *       how to render. The chat panel does not see the supervisor
 *       specifics — that is the point of this backend.</li>
 * </ol>
 *
 * <p>R7.5 follow-up: replace polling with the supervisor's push
 * channel (the supervisor already supports WebSocket-style push
 * via {@code ChildEventRecord} append). Until then, the 200 ms
 * poll keeps latency at "feels-live" for short messages.
 */
class DaemonBackend(
    private val lockFile: Path,
    private val cwd: Path,
    private val model: String? = null
) : AetherCodeBackend {

    private val log = logger<DaemonBackend>()

    private val mapper: ObjectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val idSeq = AtomicLong(1)
    private val lock = ReentrantLock()
    private var socket: Socket? = null
    private var writer: Writer? = null
    private var reader: BufferedReader? = null

    @Volatile private var currentChildId: String? = null

    override val id: String = "daemon"
    override val label: String = "AetherCode Daemon (shared with TUI/Desktop)"

    override fun isAvailable(): Boolean = try {
        // Cheap probe: does the lock file exist with a parseable port?
        // We avoid opening the socket here so the check is fast and
        // free of side effects; the actual connection happens on the
        // first query() call.
        if (!Files.exists(lockFile)) return false
        val port = Files.readAllLines(lockFile, StandardCharsets.UTF_8)
            .firstOrNull()?.trim()?.toIntOrNull() ?: return false
        port in 1..65535
    } catch (e: Exception) {
        log.debug("daemon probe failed: ${e.message}")
        false
    }

    override fun query(prompt: String): Sequence<StreamEvent> = sequence {
        val childId = try {
            spawn(prompt)
        } catch (e: Exception) {
            log.warn("task/spawn failed: ${e.message}", e)
            yield(StreamEvent.SideNote("daemon: spawn failed — ${e.message}"))
            return@sequence
        }
        currentChildId = childId
        try {
            var since = 0
            val deadline = System.currentTimeMillis() + 5L * 60_000L // 5 min cap
            while (System.currentTimeMillis() < deadline) {
                val events = try { attach(childId, since) } catch (e: Exception) {
                    log.warn("task/attach failed: ${e.message}", e)
                    break
                }
                for (ev in events) {
                    yield(toStreamEvent(ev))
                    since = maxOf(since, ev.seq ?: since)
                }
                if (events.isEmpty()) Thread.sleep(200) else Thread.sleep(20)
                if (isTerminal(events.lastOrNull())) break
            }
        } finally {
            currentChildId = null
        }
    }

    override fun abort() {
        val childId = currentChildId ?: return
        try { callVoid("task/kill", mapOf("childId" to childId, "reason" to "user-abort")) }
        catch (e: Exception) { log.warn("task/kill failed: ${e.message}", e) }
    }

    override fun dispose() {
        lock.withLock {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            writer = null
            reader = null
        }
    }

    // --- RPC primitives --------------------------------------------------

    private fun spawn(prompt: String): String {
        val params = buildMap<String, Any> {
            put("prompt", prompt)
            put("cwd", cwd.toString())
            if (model != null) put("model", model)
        }
        @Suppress("UNCHECKED_CAST")
        val result = call("task/spawn", params) as Map<String, Any?>
        return result["childId"] as? String
            ?: throw IOException("task/spawn returned no childId: $result")
    }

    private fun attach(childId: String, since: Int): List<SupervisorEvent> {
        @Suppress("UNCHECKED_CAST")
        val result = call("task/attach", mapOf("childId" to childId, "since" to since)) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val raw = (result["events"] as? List<Map<String, Any?>>) ?: emptyList()
        return raw.map { SupervisorEvent.fromMap(it) }
    }

    private fun call(method: String, params: Any?): Any? {
        lock.withLock {
            ensureConnected()
            val id = idSeq.getAndIncrement()
            val req = mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
            val line = mapper.writeValueAsString(req)
            writer!!.write(line); writer!!.write("\n"); writer!!.flush()
            val respLine = reader!!.readLine() ?: throw IOException("daemon closed connection")
            val resp = mapper.readValue(respLine, Map::class.java) as Map<String, Any?>
            if (resp.containsKey("error")) {
                val err = resp["error"] as Map<String, Any?>
                throw IOException("rpc error ${err["code"]}: ${err["message"]}")
            }
            return resp["result"]
        }
    }

    private fun callVoid(method: String, params: Any?) { call(method, params) }

    private fun ensureConnected() {
        if (socket?.isConnected == true) return
        if (!Files.exists(lockFile)) {
            throw IOException("daemon lock file not found: $lockFile (is the supervisor running?)")
        }
        val port = Files.readAllLines(lockFile, StandardCharsets.UTF_8)
            .firstOrNull()?.trim()?.toIntOrNull()
            ?: throw IOException("lock file $lockFile has no port")
        socket = Socket("127.0.0.1", port)
        writer = OutputStreamWriter(socket!!.getOutputStream(), StandardCharsets.UTF_8)
        reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), StandardCharsets.UTF_8))
        log.info("daemon backend connected to 127.0.0.1:$port")
    }

    // --- event conversion ------------------------------------------------

    private fun isTerminal(ev: SupervisorEvent?): Boolean {
        if (ev == null) return false
        val t = ev.type?.lowercase() ?: return false
        return t in setOf("end", "run_end", "stop", "exit", "complete", "completed", "failed", "cancelled")
    }

    /** Convert a supervisor event into a core {@code StreamEvent} the chat panel renders. */
    private fun toStreamEvent(ev: SupervisorEvent): StreamEvent {
        return when (ev.type?.lowercase()) {
            "delta", "text_delta", "message_delta" ->
                StreamEvent.TextDelta(ev.text ?: "")
            "tool_call", "tool_use" ->
                StreamEvent.ToolUseStart(
                    id = ev.seq?.toString() ?: "tu-${System.nanoTime()}",
                    name = ev.toolName ?: "tool",
                    input = parseInput(ev.input)
                )
            "tool_output_delta", "tool_stream" ->
                StreamEvent.ToolOutputDelta(
                    id = ev.seq?.toString() ?: "tod-${System.nanoTime()}",
                    text = ev.text ?: ""
                )
            "tool_result" ->
                StreamEvent.ToolResult(
                    id = ev.seq?.toString() ?: "tr-${System.nanoTime()}",
                    content = ev.text ?: "",
                    isError = ev.error == true
                )
            "run_start" ->
                StreamEvent.RunStart(
                    runId = ev.runId ?: ev.seq?.toString() ?: "run",
                    model = model ?: "daemon"
                )
            "run_end", "end", "stop", "exit" ->
                StreamEvent.RunEnd(stopReason = ev.stopReason ?: "end", finalBlocks = emptyList())
            "usage" ->
                StreamEvent.Usage(
                    inputTokens = (ev.usage?.get("inputTokens") as? Number)?.toInt() ?: 0,
                    outputTokens = (ev.usage?.get("outputTokens") as? Number)?.toInt() ?: 0
                )
            else ->
                StreamEvent.SideNote(
                    kind = ev.type ?: "event",
                    message = ev.text ?: ev.type ?: "(event)"
                )
        }
    }

    /** Best-effort parse of the supervisor's free-form input string into a Map. */
    private fun parseInput(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(raw, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            mapOf("raw" to raw)
        }
    }
}

/**
 * Lightweight projection of a supervisor {@code ChildEventRecord}.
 * We only consume the fields that map onto core {@code StreamEvent}
 * variants; unknown fields are tolerated by Jackson.
 */
internal data class SupervisorEvent(
    val seq: Int?,
    val type: String?,
    val text: String?,
    val toolName: String?,
    val input: String?,
    val error: Boolean?,
    val runId: String?,
    val stopReason: String?,
    val usage: Map<String, Any>?
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): SupervisorEvent = SupervisorEvent(
            seq = (m["seq"] as? Number)?.toInt(),
            type = m["type"] as? String ?: m["kind"] as? String,
            text = m["text"] as? String ?: m["content"] as? String,
            toolName = m["toolName"] as? String ?: m["name"] as? String,
            input = m["input"]?.toString(),
            error = m["error"] as? Boolean ?: m["isError"] as? Boolean,
            runId = m["runId"] as? String,
            stopReason = m["stopReason"] as? String ?: m["reason"] as? String,
            usage = m["usage"] as? Map<String, Any>
        )
    }
}

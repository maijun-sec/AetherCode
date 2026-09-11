package org.aethercode.idea.backend

import org.aethercode.core.stream.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/**
 * R7: tests for the daemon wire format conversion. We do not need a
 * running IDE platform for these — the {@link DaemonBackend} speaks
 * a line-delimited JSON-RPC dialect over a TCP loopback, and the
 * conversion from supervisor events to core {@code StreamEvent}
 * is pure logic.
 */
class DaemonBackendWireTest {

    @Test
    fun `daemon unavailable when lock file missing`() {
        val tmp = Files.createTempDirectory("aethercode-daemon-test").toAbsolutePath()
        val lock = tmp.resolve("missing.port")
        val backend = DaemonBackend(lock = lock, cwd = tmp)
        assertFalse("daemon should report unavailable when no lock file", backend.isAvailable())
    }

    @Test
    fun `daemon unavailable when lock file has no port`() {
        val tmp = Files.createTempDirectory("aethercode-daemon-test").toAbsolutePath()
        val lock = tmp.resolve("empty.port")
        Files.write(lock, "\n".toByteArray())
        val backend = DaemonBackend(lock = lock, cwd = tmp)
        assertFalse("daemon should report unavailable for empty lock", backend.isAvailable())
    }

    @Test
    fun `daemon available when lock file has valid port`() {
        val ss = ServerSocket(0)
        val port = ss.localPort
        ss.close() // closed but the port number is what matters for the probe
        val tmp = Files.createTempDirectory("aethercode-daemon-test").toAbsolutePath()
        val lock = tmp.resolve("supervisor.port")
        Files.write(lock, port.toString().toByteArray())
        val backend = DaemonBackend(lock = lock, cwd = tmp)
        assertTrue("daemon should report available when port is in range", backend.isAvailable())
    }

    /**
     * The conversion path is internal to DaemonBackend, so we test
     * the public surface: that a query against a stub supervisor
     * yields StreamEvent values in the expected order. We stand
     * up an in-process line-delimited JSON-RPC server that mimics
     * the supervisor's spawn → attach sequence.
     */
    @Test
    fun `query yields run_start then deltas then end`() {
        val ss = ServerSocket(0)
        val port = ss.localPort
        val tmp = Files.createTempDirectory("aethercode-daemon-test").toAbsolutePath()
        val lock = tmp.resolve("supervisor.port")
        Files.write(lock, port.toString().toByteArray())

        // Start a minimal stub supervisor.
        val serverThread = Thread { runStubSupervisor(ss) }
        serverThread.isDaemon = true
        serverThread.start()
        Thread.sleep(100) // give the server a moment to bind

        val backend = DaemonBackend(lock = lock, cwd = tmp, model = "stub-model")
        val events = backend.query("hello world").toList()

        assertTrue("expected at least one event, got ${events.size}", events.isNotEmpty())
        val first = events.first()
        assertTrue("expected runStart, got $first", first is StreamEvent.RunStart)
        assertEquals("stub-model", (first as StreamEvent.RunStart).model())
        val last = events.last()
        assertTrue("expected runEnd, got $last", last is StreamEvent.RunEnd)
    }

    @Test
    fun `query yields tool_use then tool_result`() {
        val ss = ServerSocket(0)
        val port = ss.localPort
        val tmp = Files.createTempDirectory("aethercode-daemon-test").toAbsolutePath()
        val lock = tmp.resolve("supervisor.port")
        Files.write(lock, port.toString().toByteArray())

        val serverThread = Thread { runStubSupervisorWithTool(ss) }
        serverThread.isDaemon = true
        serverThread.start()
        Thread.sleep(100)

        val backend = DaemonBackend(lock = lock, cwd = tmp)
        val events = backend.query("use the tool").toList()

        val toolUse = events.filterIsInstance<StreamEvent.ToolUseStart>().firstOrNull()
        val toolResult = events.filterIsInstance<StreamEvent.ToolResult>().firstOrNull()
        assertNotNull("expected at least one tool_use event", toolUse)
        assertNotNull("expected at least one tool_result event", toolResult)
    }

    // --- stubs ---------------------------------------------------------

    private fun runStubSupervisor(ss: ServerSocket) {
        try {
            val socket = ss.accept()
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()
            // Read first request (task/spawn), respond with a childId.
            val req1 = reader.readLine() ?: return
            val childId = "child-test"
            writer.write("""{"jsonrpc":"2.0","id":1,"result":{"childId":"$childId"}}""" + "\n")
            writer.flush()
            // Read second request (task/attach), respond with runStart + delta + runEnd.
            val req2 = reader.readLine() ?: return
            val events = """
                {"seq":1,"type":"run_start","runId":"run-1","model":"stub-model"},
                {"seq":2,"type":"text_delta","text":"hi"},
                {"seq":3,"type":"run_end","stopReason":"end"}
            """.trimIndent()
            writer.write("""{"jsonrpc":"2.0","id":2,"result":{"events":[$events]}}""" + "\n")
            writer.flush()
        } catch (_: Exception) {}
    }

    private fun runStubSupervisorWithTool(ss: ServerSocket) {
        try {
            val socket = ss.accept()
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()
            reader.readLine() ?: return
            writer.write("""{"jsonrpc":"2.0","id":1,"result":{"childId":"child-x"}}""" + "\n")
            writer.flush()
            reader.readLine() ?: return
            val events = """
                {"seq":1,"type":"tool_call","toolName":"bash","input":"{\"cmd\":\"ls\"}"},
                {"seq":2,"type":"tool_result","toolName":"bash","text":"file.txt\n","error":false},
                {"seq":3,"type":"run_end","stopReason":"end"}
            """.trimIndent()
            writer.write("""{"jsonrpc":"2.0","id":2,"result":{"events":[$events]}}""" + "\n")
            writer.flush()
        } catch (_: Exception) {}
    }
}

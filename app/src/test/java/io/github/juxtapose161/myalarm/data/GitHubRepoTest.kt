package io.github.juxtapose161.myalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread

/** The contents API client against a tiny fake GitHub. */
class GitHubRepoTest {

    data class Req(val method: String, val path: String, val auth: String?, val body: String)

    private fun serve(responses: List<Pair<Int, String>>, block: (String) -> Unit): List<Req> {
        val server = ServerSocket(0)
        val seen = mutableListOf<Req>()
        val t = thread(isDaemon = true) {
            for ((code, body) in responses) {
                server.accept().use { s ->
                    val input = s.getInputStream().buffered()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    val lines = head.lines()
                    val (method, path) = lines[0].split(' ').let { it[0] to it[1] }
                    val len = lines.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val bytes = ByteArray(len)
                    var read = 0
                    while (read < len) read += input.read(bytes, read, len - read)
                    val auth = lines.firstOrNull { it.lowercase().startsWith("authorization:") }?.substringAfter(':')?.trim()
                    synchronized(seen) { seen += Req(method, path, auth, bytes.decodeToString()) }
                    val out = body.toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(out); flush()
                    }
                }
            }
        }
        try { block("http://127.0.0.1:${server.localPort}") } finally { t.join(2000); server.close() }
        return seen
    }

    @Test fun writeCreatesNewFile() {
        val reqs = serve(listOf(404 to """{"message":"Not Found"}""", 201 to "{}")) { api ->
            GitHubRepo("me/data", "tok", api).write("answers/a b.json", "{\"x\":\"привет\"}", "phone: answer")
        }
        assertEquals("GET", reqs[0].method)
        assertEquals("/repos/me/data/contents/answers/a%20b.json", reqs[0].path)
        assertEquals("Bearer tok", reqs[0].auth)
        assertEquals("PUT", reqs[1].method)
        assertTrue(!reqs[1].body.contains("\"sha\""))
        val content = Regex("\"content\":\"([^\"]+)\"").find(reqs[1].body)!!.groupValues[1]
        assertEquals("{\"x\":\"привет\"}", Base64.getDecoder().decode(content).decodeToString())
    }

    @Test fun writeReplacesWithSha() {
        val existing = """{"content":"e30=\n","sha":"abc123"}"""
        val reqs = serve(listOf(200 to existing, 200 to "{}")) { api ->
            GitHubRepo("me/data", "tok", api).write("phone/state.json", "{}", "m")
        }
        assertTrue(reqs[1].body.contains("\"sha\":\"abc123\""))
    }

    @Test fun readListAndMissing() {
        val file = """{"content":"${Base64.getMimeEncoder().encodeToString("{\"level\":\"B2\"}".toByteArray())}","sha":"s1"}"""
        val list = """[{"name":"t1.json","path":"tasks/t1.json","sha":"x","type":"file","size":3}]"""
        serve(listOf(200 to file, 200 to list, 404 to "{}", 404 to "{}")) { api ->
            val gh = GitHubRepo("me/data", "tok", api)
            assertEquals("{\"level\":\"B2\"}", gh.read("profile.json")!!.text)
            assertEquals("tasks/t1.json", gh.list("tasks").single().path)
            assertNull(gh.read("nope.json"))
            assertTrue(gh.list("reviews").isEmpty())
        }
    }

    @Test fun errorsCarryTheMessage() {
        serve(listOf(401 to """{"message":"Bad credentials"}""")) { api ->
            val e = runCatching { GitHubRepo("me/data", "tok", api).ping() }.exceptionOrNull()
            assertTrue(e is GitHubRepo.HttpException && e.code == 401 && e.message!!.contains("Bad credentials"))
        }
    }
}

package io.github.juxtapose161.myalarm.llm

import io.github.juxtapose161.myalarm.course.AppJson
import io.github.juxtapose161.myalarm.course.ClaudeSettings
import io.github.juxtapose161.myalarm.course.Effort
import io.github.juxtapose161.myalarm.course.Schemas
import io.github.juxtapose161.myalarm.course.Verdict
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Checks the request the SDK sends and the parsing of the reply, against a local fake server. */
class ClaudeClientTest {

    private fun reply(text: String) = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
         "content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}],
         "stop_reason":"end_turn","stop_sequence":null,
         "usage":{"input_tokens":10,"output_tokens":5}}
    """.trimIndent()

    @Test fun sendsStructuredRequestAndParsesReply() {
        val bodies = mutableListOf<String>()
        val headers = mutableListOf<String?>()
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            server.accept().use { sock ->
                val input = sock.getInputStream().buffered()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                val lines = head.lines()
                headers += lines.firstOrNull { it.lowercase().startsWith("anthropic-beta:") }?.substringAfter(':')?.trim()
                val length = lines.first { it.lowercase().startsWith("content-length:") }.substringAfter(':').trim().toInt()
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) read += input.read(bytes, read, length - read)
                bodies += bytes.decodeToString()
                val out = reply("""{"acceptable":true,"comment":"синоним"}""").toByteArray()
                val os = sock.getOutputStream()
                os.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
                os.write(out)
                os.flush()
            }
        }
        try {
            val client = ClaudeClient(ClaudeSettings(apiKey = "test"), "http://127.0.0.1:${server.localPort}")
            val text = runBlocking { client.json("hello", Schemas.verdict, Effort.LOW, 1000) }
            val v = AppJson.decodeFromString(Verdict.serializer(), text)
            assertTrue(v.acceptable)

            val body = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
            assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
            val output = body["output_config"]!!.jsonObject
            assertEquals("low", output["effort"]!!.jsonPrimitive.content)
            val format = output["format"]!!.jsonObject
            assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
            val schema = format["schema"] as JsonObject
            assertEquals(listOf("acceptable", "comment"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertTrue(body["system"].toString().contains("English tutor"))
            assertEquals("server-side-fallback-2026-07-01", headers.single())
        } finally {
            server.close()
            worker.join(1000)
        }
    }
}

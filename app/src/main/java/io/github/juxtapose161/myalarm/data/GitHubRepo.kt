package io.github.juxtapose161.myalarm.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * A tiny client for the GitHub "contents" API: read, list, write and delete files of one
 * repository with a fine-grained token that can access only that repository.
 */
class GitHubRepo(
    /** "owner/name". */
    private val repo: String,
    private val token: String,
    private val api: String = "https://api.github.com",
) {
    class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

    @Serializable
    data class Entry(val name: String, val path: String, val sha: String, val type: String)

    data class FileContent(val text: String, val sha: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String) =
        "$api/repos/$repo/contents/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun request(method: String, url: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "my-alarm-english")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }

    private fun fail(code: Int, text: String): Nothing {
        val msg = runCatching { json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
        throw HttpException(code, msg ?: text.take(200))
    }

    /** Checks that the repository is reachable with the token. */
    fun ping() {
        val (code, text) = request("GET", "$api/repos/$repo")
        if (code != 200) fail(code, text)
    }

    /** Files of a directory; empty if it does not exist. */
    fun list(dir: String): List<Entry> {
        val (code, text) = request("GET", url(dir))
        if (code == 404) return emptyList()
        if (code != 200) fail(code, text)
        val el = json.parseToJsonElement(text)
        if (el !is JsonArray) return emptyList()
        return el.map { json.decodeFromJsonElement(Entry.serializer(), it) }
    }

    fun read(path: String): FileContent? {
        val (code, text) = request("GET", url(path))
        if (code == 404) return null
        if (code != 200) fail(code, text)
        val o = json.parseToJsonElement(text).jsonObject
        val content = o["content"]?.jsonPrimitive?.content.orEmpty()
        val bytes = Base64.getMimeDecoder().decode(content)
        return FileContent(bytes.decodeToString(), o["sha"]!!.jsonPrimitive.content)
    }

    /** Creates or replaces a file. */
    fun write(path: String, text: String, message: String) {
        val sha = read(path)?.sha
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
            if (sha != null) put("sha", sha)
        }
        val (code, resp) = request("PUT", url(path), body.toString())
        if (code != 200 && code != 201) fail(code, resp)
    }

    fun delete(path: String, sha: String, message: String) {
        val body = JsonObject(mapOf("message" to JsonPrimitive(message), "sha" to JsonPrimitive(sha)))
        val (code, resp) = request("DELETE", url(path), body.toString())
        if (code != 200 && code != 404) fail(code, resp)
    }
}

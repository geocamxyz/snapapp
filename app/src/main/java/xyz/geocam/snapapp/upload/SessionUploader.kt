package xyz.geocam.snapapp.upload

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class UploadResult {
    data class Success(val projectUrl: String) : UploadResult()
    data class Failure(val message: String) : UploadResult()
}

class SessionUploader(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // final chunk triggers server processing — needs time
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun upload(
        dbFile: File,
        baseUrl: String,
        onProgress: (sent: Long, total: Long) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {
        val filename = dbFile.name
        val total = dbFile.length()
        val uploadId = getOrCreateUploadId(filename)
        val endpoint = baseUrl.trimEnd('/') + UPLOAD_PATH

        var offset = checkServerOffset(uploadId, endpoint)
        onProgress(offset, total)

        var backoffMs = BACKOFF_START_MS
        val deadline = System.currentTimeMillis() + MAX_RETRY_MS

        RandomAccessFile(dbFile, "r").use { raf ->
            while (offset < total) {
                if (System.currentTimeMillis() > deadline) {
                    return@withContext UploadResult.Failure("Upload timed out after repeated failures")
                }

                val end = minOf(offset + CHUNK_SIZE - 1, total - 1)
                val chunkLen = (end - offset + 1).toInt()
                val chunk = ByteArray(chunkLen)
                raf.seek(offset)
                raf.readFully(chunk)

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Content-Type", "application/octet-stream")
                    .addHeader("Content-Range", "bytes $offset-$end/$total")
                    .addHeader("X-Upload-Id", uploadId)
                    .addHeader("X-Filename", filename)
                    .post(chunk.toRequestBody("application/octet-stream".toMediaType()))
                    .build()

                val outcome = try {
                    val response = http.newCall(request).execute()
                    val body = response.body?.string() ?: "{}"
                    parseResponse(response.code, body)
                } catch (e: Exception) {
                    ChunkOutcome.Retry("Network error: ${e.message}")
                }

                when (outcome) {
                    is ChunkOutcome.Advance -> {
                        offset = outcome.received
                        onProgress(offset, total)
                        backoffMs = BACKOFF_START_MS
                        if (outcome.complete) {
                            clearUploadId(filename)
                            return@withContext UploadResult.Success(outcome.projectUrl ?: "")
                        }
                    }
                    is ChunkOutcome.Seek -> {
                        offset = outcome.expected
                        backoffMs = BACKOFF_START_MS
                    }
                    is ChunkOutcome.Retry -> {
                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, BACKOFF_MAX_MS)
                    }
                    is ChunkOutcome.Fatal -> {
                        return@withContext UploadResult.Failure(outcome.message)
                    }
                }
            }
        }
        UploadResult.Failure("Upload loop ended unexpectedly")
    }

    private fun parseResponse(code: Int, body: String): ChunkOutcome {
        return when {
            code == 200 -> {
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return ChunkOutcome.Fatal("Unparseable response: $body")
                val complete = json.optBoolean("complete", false)
                val received = json.optLong("received", 0L)
                if (complete) {
                    val url = json.optString("project_url").takeIf { it.isNotBlank() }
                    ChunkOutcome.Advance(received, true, url)
                } else {
                    ChunkOutcome.Advance(received, false, null)
                }
            }
            code == 409 -> {
                val json = runCatching { JSONObject(body) }.getOrNull()
                ChunkOutcome.Seek(json?.optLong("expected", 0L) ?: 0L)
            }
            code == 400 && body.contains("short_body") -> {
                ChunkOutcome.Retry("Short body — retrying chunk")
            }
            code == 400 -> ChunkOutcome.Fatal("Server rejected upload (400): $body")
            code == 413 -> ChunkOutcome.Fatal("File too large (413): $body")
            code >= 500 -> ChunkOutcome.Retry("Server error $code — will retry")
            else -> ChunkOutcome.Fatal("Unexpected response $code: $body")
        }
    }

    private fun checkServerOffset(uploadId: String, endpoint: String): Long {
        return try {
            val req = Request.Builder().url("$endpoint/$uploadId/status").get().build()
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optBoolean("exists", false)) json.optLong("received", 0L) else 0L
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun getOrCreateUploadId(filename: String): String {
        val prefs = context.getSharedPreferences(PREFS_UPLOAD_IDS, Context.MODE_PRIVATE)
        return prefs.getString(filename, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(filename, it).apply()
        }
    }

    private fun clearUploadId(filename: String) {
        context.getSharedPreferences(PREFS_UPLOAD_IDS, Context.MODE_PRIVATE)
            .edit().remove(filename).apply()
    }

    private sealed class ChunkOutcome {
        data class Advance(val received: Long, val complete: Boolean, val projectUrl: String?) : ChunkOutcome()
        data class Seek(val expected: Long) : ChunkOutcome()
        data class Retry(val reason: String) : ChunkOutcome()
        data class Fatal(val message: String) : ChunkOutcome()
    }

    companion object {
        const val UPLOAD_PATH = "/api/mobile/upload"
        const val CHUNK_SIZE = 8L * 1024 * 1024   // 8 MiB
        const val BACKOFF_START_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val MAX_RETRY_MS = 5 * 60 * 1_000L  // 5 min
        const val PREFS_UPLOAD_IDS = "upload_ids"
    }
}

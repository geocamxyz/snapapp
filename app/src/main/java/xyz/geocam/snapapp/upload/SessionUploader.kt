package xyz.geocam.snapapp.upload

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.Executors

class SessionUploader {

    private val http = OkHttpClient()
    private val executor = Executors.newCachedThreadPool()

    fun upload(dbFile: File, uploadUrl: String, onResult: (success: Boolean) -> Unit) {
        executor.execute {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        dbFile.name,
                        dbFile.asRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()
                val request = Request.Builder().url(uploadUrl).post(body).build()
                val response = http.newCall(request).execute()
                onResult(response.isSuccessful)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }
}

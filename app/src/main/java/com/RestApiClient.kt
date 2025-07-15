// In a new file: network/RestApiClient.kt
package com.gemweblive.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class RestApiClient(private val apiKey: String) {

    private val client = OkHttpClient()

    // The callback will be used to return the result to MainActivity
    interface RestCallback {
        fun onSuccess(responseText: String)
        fun onFailure(e: IOException)
    }

    fun generateContent(text: String, callback: RestCallback) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

        val jsonPayload = """
            {
                "contents": [{
                    "parts": [{
                        "text": "$text"
                    }]
                }]
            }
        """.trimIndent()

        val requestBody = jsonPayload.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let {
                    callback.onSuccess(it)
                } ?: callback.onFailure(IOException("Response body was null"))
            }
        })
    }
}

package com.umbra.app.data.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/**
 * Counts HTTP body bytes as they actually stream (not Content-Length, which can be missing or
 * wrong), split into media (image/video/audio) and everything else. WebSocket frames are counted
 * separately by the relay client, so a 101 upgrade response contributes nothing here.
 */
class TrafficCountingInterceptor(private val meter: TrafficMeter) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        request.body?.contentLength()?.takeIf { it > 0 }?.let(meter::recordHttpSent)
        val response = chain.proceed(request)
        if (response.code == 101) return response
        val body = response.body
        val mediaType = body.contentType()?.type?.lowercase()
        val isMedia = mediaType == "image" || mediaType == "video" || mediaType == "audio"
        val counting = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) meter.recordHttpReceived(isMedia, read)
                return read
            }
        }
        return response.newBuilder()
            .body(counting.buffer().asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }
}

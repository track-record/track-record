package com.trackrecord.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * Runs the call and returns what [read] makes of its response. [read] runs on OkHttp's thread and may block while
 * the body arrives. Cancelling the coroutine cancels the call at once, also while it waits for the answer or [read]
 * reads it: this returns right away, and the call's socket is closed, so [read] ends with an [IOException] (REVIEW-3
 * B-14: a blocking `execute()` held Cancel up until the answer or the timeout).
 */
suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWith(Result.failure(e))

            override fun onResponse(call: Call, response: Response) = continuation.resumeWith(runCatching { response.use(read) })
        },
    )
}

package com.helix.core.policy.network

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** A transient cause is not proof that a request was not executed. Callers must establish safety separately. */
fun isTransientNetworkFailure(failure: Throwable): Boolean {
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    if (causes.any {
            it is SSLException || it is java.util.concurrent.CancellationException ||
                it is InterruptedException
        }
    ) {
        return false
    }
    return causes.any {
        it is ConnectException || it is SocketTimeoutException || it is SocketException || it is EOFException
    }
}

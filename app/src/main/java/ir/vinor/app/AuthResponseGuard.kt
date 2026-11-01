package ir.vinor.app

import java.util.concurrent.atomic.AtomicLong

/** Prevent late guest responses from overwriting a newer OTP/authentication session. */
internal class AuthResponseGuard {
    private val generation = AtomicLong(0L)
    fun capture(): Long = generation.get()
    fun beginCredentials(): Long = generation.incrementAndGet()
    fun stateChanged(): Long = generation.incrementAndGet()
    fun isCurrent(requestGeneration: Long): Boolean = generation.get() == requestGeneration
    fun shouldExpire(
        hadSessionAtRequest: Boolean,
        credentialRequest: Boolean,
        status: Int,
        silentGuestResponse: Boolean,
    ): Boolean =
        hadSessionAtRequest && !credentialRequest && (status == 401 || silentGuestResponse)
}

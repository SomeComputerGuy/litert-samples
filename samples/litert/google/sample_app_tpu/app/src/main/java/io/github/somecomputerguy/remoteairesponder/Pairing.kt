package io.github.somecomputerguy.remoteairesponder

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairs coordinators with this phone and checks their tokens.
 *
 * Pairing runs over the TLS port, in two steps, so the person approving on the phone can
 * check that nobody is intercepting the connection:
 *
 *  1. `start`: the coordinator sends its name and SHA-256(client nonce); the phone replies
 *     with its own random nonce.
 *  2. `finish`: the coordinator reveals the client nonce. Both sides now compute a 6-digit
 *     code from (certificate fingerprint, client nonce, server nonce), using the certificate
 *     each side actually sees. An interceptor presents a different certificate, and because
 *     the client committed to its nonce first it can't steer the codes to match.
 *
 * The phone shows the code and asks for a fingerprint or screen lock; on approval the
 * coordinator gets its own token. Only a hash of each token is stored.
 */
object Pairing {

    const val CODE_TIMEOUT_MS = 120_000L
    private const val START_TIMEOUT_MS = 60_000L
    private const val PREFS = "PairedClients"
    private const val KEY_CLIENTS = "clients"

    data class Client(val id: String, val name: String, val tokenHash: String, val pairedAt: Long)

    /** A pairing waiting for the person at the phone to approve or deny it. */
    data class Request(
        val id: String,
        val clientName: String,
        val remote: String,
        val code: String,
        internal val decision: CompletableDeferred<Boolean> = CompletableDeferred()
    )

    sealed interface Outcome {
        data class Approved(val client: Client, val token: String) : Outcome
        data class Rejected(val status: Int, val message: String) : Outcome
    }

    private class Started(
        val clientName: String,
        val commitment: ByteArray,
        val serverNonce: ByteArray,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val random = SecureRandom()
    private val started = ConcurrentHashMap<String, Started>()
    private val _pending = MutableStateFlow<Request?>(null)

    /** The pairing waiting for approval, shown by [PairingActivity]. */
    val pending: StateFlow<Request?> = _pending

    // ---------------------------------------------------------------- Protocol

    /** Step 1. Returns the pairing ID and the server nonce. */
    fun start(clientName: String, commitmentHex: String): Pair<String, ByteArray> {
        val commitment = hexToBytes(commitmentHex)
        require(commitment.size == 32) { "commitment must be 32 bytes of hex" }
        val now = System.currentTimeMillis()
        started.entries.removeIf { now - it.value.createdAt > START_TIMEOUT_MS }
        val id = UUID.randomUUID().toString()
        val nonce = ByteArray(32).also(random::nextBytes)
        started[id] = Started(clientName.trim().take(64).ifEmpty { "Unnamed coordinator" }, commitment, nonce)
        return id to nonce
    }

    /**
     * Step 2. Waits for the person at the phone, up to [CODE_TIMEOUT_MS]. [onPrompt] is
     * called once the request is ready to show.
     */
    suspend fun finish(
        context: Context,
        pairingId: String,
        clientNonceHex: String,
        remote: String,
        onPrompt: (Request) -> Unit
    ): Outcome {
        val start = started.remove(pairingId)
            ?: return Outcome.Rejected(404, "Unknown or expired pairing; start again")
        val clientNonce = hexToBytes(clientNonceHex)
        if (!MessageDigest.isEqual(sha256(clientNonce), start.commitment)) {
            return Outcome.Rejected(400, "client_nonce does not match the commitment")
        }

        val request = Request(
            id = pairingId,
            clientName = start.clientName,
            remote = remote,
            code = code(ServerIdentity.fingerprintBytes, clientNonce, start.serverNonce)
        )
        synchronized(this) {
            if (_pending.value != null) {
                return Outcome.Rejected(409, "Another pairing is waiting for approval on the phone")
            }
            _pending.value = request
        }
        try {
            onPrompt(request)
            val approved = withTimeoutOrNull(CODE_TIMEOUT_MS) { request.decision.await() }
            return when (approved) {
                true -> {
                    val token = newToken()
                    val client = Client(UUID.randomUUID().toString(), start.clientName, hashToken(token), System.currentTimeMillis())
                    saveClients(context, clients(context) + client)
                    Outcome.Approved(client, token)
                }
                false -> Outcome.Rejected(403, "Pairing was denied on the phone")
                null -> Outcome.Rejected(408, "Nobody approved the pairing on the phone in time")
            }
        } finally {
            _pending.compareAndSet(request, null)
        }
    }

    /** Called by [PairingActivity] after authentication, or when the person denies it. */
    fun decide(requestId: String, approved: Boolean) {
        _pending.value?.takeIf { it.id == requestId }?.decision?.complete(approved)
    }

    /** The 6-digit comparison code, formatted "123 456". The coordinator computes the same. */
    fun code(fingerprint: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): String {
        val digest = sha256(fingerprint + clientNonce + serverNonce)
        val value = ((digest[0].toLong() and 0xff) shl 24 or
            ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or
            (digest[3].toLong() and 0xff)) % 1_000_000
        return "%06d".format(value).let { "${it.take(3)} ${it.drop(3)}" }
    }

    // ---------------------------------------------------------------- Paired clients

    fun clients(context: Context): List<Client> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLIENTS, null)
            ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Client(o.getString("id"), o.getString("name"), o.getString("token_sha256"), o.getLong("paired_at"))
        }
    }

    fun revoke(context: Context, clientId: String) {
        saveClients(context, clients(context).filterNot { it.id == clientId })
    }

    /** The paired client this token belongs to, or null. */
    fun authenticate(context: Context, token: String): Client? {
        val hash = hashToken(token).toByteArray()
        return clients(context).firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), hash) }
    }

    private fun saveClients(context: Context, clients: List<Client>) {
        val array = JSONArray()
        clients.forEach {
            array.put(JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("token_sha256", it.tokenHash)
                .put("paired_at", it.pairedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CLIENTS, array.toString()).apply()
    }

    // ---------------------------------------------------------------- Helpers

    private fun newToken(): String =
        "rai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun hashToken(token: String): String = toHex(sha256(token.toByteArray(Charsets.UTF_8)))

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" }) { "expected hex" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

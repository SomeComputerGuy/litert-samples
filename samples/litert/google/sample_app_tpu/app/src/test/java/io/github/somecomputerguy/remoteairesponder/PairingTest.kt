package io.github.somecomputerguy.remoteairesponder

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingTest {

    @Test
    fun codeMatchesTheCoordinator() {
        // Same inputs as coordinator/remoteai.py sas_code(); both sides must agree.
        val fingerprint = ByteArray(32) { it.toByte() }
        val code = Pairing.code(fingerprint, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        assertEquals("125 477", code)
    }
}

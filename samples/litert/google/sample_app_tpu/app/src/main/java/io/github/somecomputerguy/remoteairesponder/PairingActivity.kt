package io.github.somecomputerguy.remoteairesponder

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Asks the person at the phone to approve a coordinator's pairing request. Approving
 * requires a fingerprint, face or the screen lock; the code must match the one the
 * coordinator shows.
 */
class PairingActivity : AppCompatActivity() {

    private var request: Pairing.Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_pairing)

        findViewById<MaterialButton>(R.id.buttonDeny).setOnClickListener {
            request?.let { Pairing.decide(it.id, false) }
            finish()
        }
        findViewById<MaterialButton>(R.id.buttonApprove).setOnClickListener { approve() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Pairing.pending.collect { pending ->
                    // Approved, denied or timed out (possibly from elsewhere): nothing left to show.
                    if (pending == null) finish() else show(pending)
                }
            }
        }
    }

    private fun show(pending: Pairing.Request) {
        request = pending
        findViewById<TextView>(R.id.textClientName).text = pending.clientName
        findViewById<TextView>(R.id.textClientAddress).text = "from ${pending.remote}"
        findViewById<TextView>(R.id.textCode).text = pending.code
        findViewById<TextView>(R.id.textPairingError).visibility = View.GONE
    }

    private fun approve() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            // Unlock first; the authentication prompt can't run over the lock screen.
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = authenticate()
            })
        } else {
            authenticate()
        }
    }

    private fun authenticate() {
        val pending = request ?: return
        // Strong biometrics can't be combined with the screen lock before Android 11.
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            showError("Set a screen lock (PIN, pattern or password) on this phone to approve pairings.")
            return
        }

        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Pairing.decide(pending.id, true)
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelling the prompt leaves the request open, to retry or deny.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        showError(errString.toString())
                    }
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Pair with ${pending.clientName}")
                .setSubtitle("Code ${pending.code}")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun showError(message: String) {
        findViewById<TextView>(R.id.textPairingError).apply {
            text = message
            visibility = View.VISIBLE
        }
    }
}

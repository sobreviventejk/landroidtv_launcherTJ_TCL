package com.tvlauncher

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One PIN for the whole launcher and the list of categories it protects.
 *
 * The salt and the hash live in a separate preferences file ([SECRET_PREFS_NAME]) so that
 * "Exportar configurações" and Android cloud backups never carry them. The hash itself is
 * PBKDF2-HMAC-SHA256 (many iterations), not a plain SHA-256, so a 4-digit PIN is expensive to
 * brute-force even when the file is copied. Wrong attempts are also rate limited.
 */
class PinPrefs(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secretPrefs = appContext.getSharedPreferences(
        SECRET_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    init {
        migrateLegacySecret()
        pruneOrphanLocks()
    }

    fun hasPin(): Boolean = secretPrefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = newSalt()
        secretPrefs.edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, pbkdf2(pin, salt, PBKDF2_ITERATIONS))
            .putString(KEY_ALGO, ALGO_PBKDF2)
            .putInt(KEY_ITERATIONS, PBKDF2_ITERATIONS)
            .apply()
        resetFailures()
    }

    fun verify(pin: String): Boolean {
        if (lockoutRemainingMs() > 0L) return false
        val salt = secretPrefs.getString(KEY_SALT, null) ?: return false
        val saved = secretPrefs.getString(KEY_HASH, null) ?: return false
        val algo = secretPrefs.getString(KEY_ALGO, ALGO_LEGACY) ?: ALGO_LEGACY
        val iterations = secretPrefs.getInt(KEY_ITERATIONS, PBKDF2_ITERATIONS)
        val computed = if (algo == ALGO_PBKDF2) {
            pbkdf2(pin, salt, iterations.coerceAtLeast(1))
        } else {
            // Old installs used a single salted SHA-256; it is upgraded after a correct PIN.
            hash(salt, pin)
        }
        val matches = MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            saved.toByteArray(Charsets.UTF_8)
        )
        if (matches) {
            resetFailures()
            if (algo != ALGO_PBKDF2) setPin(pin)
        } else {
            registerFailure()
        }
        return matches
    }

    /** Milliseconds left before another attempt is accepted; 0 when the PIN is not locked. */
    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun failedAttempts(): Int = prefs.getInt(KEY_FAILURES, 0)

    fun lockedIds(): Set<String> = prefs.getStringSet(KEY_LOCKED, null)?.toSet() ?: emptySet()

    fun isLocked(categoryId: String): Boolean = categoryId in lockedIds()

    fun setLocked(categoryId: String, locked: Boolean) {
        val ids = lockedIds().toMutableSet()
        if (locked) ids.add(categoryId) else ids.remove(categoryId)
        prefs.edit().putStringSet(KEY_LOCKED, ids).apply()
    }

    /**
     * Removes locks saved without a PIN (for example a settings file restored on another TV).
     * Without this the category would ask for a PIN that does not exist anymore.
     */
    fun pruneOrphanLocks() {
        if (!secretPrefs.contains(KEY_HASH) && prefs.contains(KEY_LOCKED)) {
            prefs.edit().remove(KEY_LOCKED).apply()
        }
    }

    private fun registerFailure() {
        val failures = failedAttempts() + 1
        val lock = lockDurationMs(failures)
        val editor = prefs.edit().putInt(KEY_FAILURES, failures)
        if (lock > 0L) {
            editor.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + lock)
        }
        editor.apply()
    }

    private fun resetFailures() {
        prefs.edit().remove(KEY_FAILURES).remove(KEY_LOCK_UNTIL).apply()
    }

    /** Copies the salt/hash of old versions to the secret file and deletes them from the old one. */
    private fun migrateLegacySecret() {
        if (secretPrefs.contains(KEY_HASH)) return
        val legacyHash = prefs.getString(KEY_HASH, null) ?: return
        val legacySalt = prefs.getString(KEY_SALT, "").orEmpty()
        secretPrefs.edit()
            .putString(KEY_HASH, legacyHash)
            .putString(KEY_SALT, legacySalt)
            .putString(KEY_ALGO, ALGO_LEGACY)
            .apply()
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).apply()
    }

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8

        private const val PREFS_NAME = "launcher_pin"
        private const val SECRET_PREFS_NAME = "launcher_pin_secret"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_ALGO = "pin_algo"
        private const val KEY_ITERATIONS = "pin_iterations"
        private const val KEY_LOCKED = "locked_categories"
        private const val KEY_FAILURES = "failed_attempts"
        private const val KEY_LOCK_UNTIL = "lock_until"

        private const val ALGO_LEGACY = "sha256"
        private const val ALGO_PBKDF2 = "pbkdf2-sha256"

        /** Cost of the PIN hash. High enough to slow guessing down, still fast on a TV. */
        private const val PBKDF2_ITERATIONS = 100_000
        private const val DERIVED_KEY_BYTES = 32

        /** Wrong attempts allowed before the first temporary lock. */
        private const val FREE_ATTEMPTS = 5
        private const val BASE_LOCK_MS = 30_000L
        private const val MAX_LOCK_MS = 15 * 60 * 1000L

        fun isValidPin(pin: String): Boolean =
            pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

        /** How long the next attempt is blocked after [failures] wrong PINs. */
        fun lockDurationMs(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0L
            val step = ((failures - FREE_ATTEMPTS) / 2).coerceIn(0, 5)
            return (BASE_LOCK_MS shl step).coerceAtMost(MAX_LOCK_MS)
        }

        /**
         * Legacy salted SHA-256 digest, kept only to read PINs saved by older versions.
         * New PINs always use [pbkdf2].
         */
        fun hash(salt: String, pin: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
            return bytes.toHex()
        }

        /** PBKDF2-HMAC-SHA256, implemented locally so it also works on Android 5 (API 21). */
        fun pbkdf2(pin: String, salt: String, iterations: Int): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val saltBytes = salt.toByteArray(Charsets.UTF_8)
            val hLen = mac.macLength
            val blocks = (DERIVED_KEY_BYTES + hLen - 1) / hLen
            val derived = ByteArray(blocks * hLen)
            val u = ByteArray(hLen)
            val t = ByteArray(hLen)

            var offset = 0
            for (block in 1..blocks) {
                mac.update(saltBytes)
                mac.update(
                    byteArrayOf(
                        (block ushr 24).toByte(),
                        (block ushr 16).toByte(),
                        (block ushr 8).toByte(),
                        block.toByte()
                    )
                )
                mac.doFinal(u, 0)
                System.arraycopy(u, 0, t, 0, hLen)
                for (round in 2..iterations) {
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (i in 0 until hLen) {
                        t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                    }
                }
                System.arraycopy(t, 0, derived, offset, hLen)
                offset += hLen
            }
            return derived.copyOf(DERIVED_KEY_BYTES).toHex()
        }

        fun newSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

package com.tvlauncher

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** One PIN for the whole launcher and the list of categories it protects. */
class PinPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, hash(salt, pin))
            .apply()
    }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val saved = prefs.getString(KEY_HASH, null) ?: return false
        return hash(salt, pin) == saved
    }

    fun lockedIds(): Set<String> = prefs.getStringSet(KEY_LOCKED, null)?.toSet() ?: emptySet()

    fun isLocked(categoryId: String): Boolean = categoryId in lockedIds()

    fun setLocked(categoryId: String, locked: Boolean) {
        val ids = lockedIds().toMutableSet()
        if (locked) ids.add(categoryId) else ids.remove(categoryId)
        prefs.edit().putStringSet(KEY_LOCKED, ids).apply()
    }

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8

        private const val PREFS_NAME = "launcher_pin"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_LOCKED = "locked_categories"

        fun isValidPin(pin: String): Boolean =
            pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

        fun hash(salt: String, pin: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun newSalt(): String = UUID.randomUUID().toString()
    }
}

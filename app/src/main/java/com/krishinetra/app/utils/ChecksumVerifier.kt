package com.krishiradar.app.utils

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChecksumVerifier @Inject constructor() {
    /** Returns true if [expected] is blank (no checksum provided) or matches SHA-256 of [file]. */
    fun verify(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        val actual = sha256(file)
        return actual.equals(expected, ignoreCase = true)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

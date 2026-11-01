package android.util

/** `android.util.Base64` with its flag semantics, over java.util.Base64. */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray = decode(str.toByteArray(Charsets.ISO_8859_1), flags)

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray = decode(input, 0, input.size, flags)

    @JvmStatic
    fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        // Lenient like Android's decoder: skips whitespace, padding optional. Accept either alphabet.
        val text = String(input, offset, len, Charsets.ISO_8859_1).filterNot { it.isWhitespace() }.trimEnd('=')
        return try {
            java.util.Base64.getDecoder().decode(text.replace('-', '+').replace('_', '/'))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad base-64", e)
        }
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String = String(encode(input, flags), Charsets.US_ASCII)

    @JvmStatic
    fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String =
        String(encode(input, offset, len, flags), Charsets.US_ASCII)

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray = encode(input, 0, input.size, flags)

    @JvmStatic
    fun encode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val out = String(encoder.encode(input.copyOfRange(offset, offset + len)), Charsets.US_ASCII)
        if (flags and NO_WRAP != 0) return out.toByteArray(Charsets.US_ASCII)
        // Android wraps at 76 chars and ends with a newline unless NO_WRAP is set.
        val nl = if (flags and CRLF != 0) "\r\n" else "\n"
        return (out.chunked(76).joinToString(nl) + nl).toByteArray(Charsets.US_ASCII)
    }
}

package android.webkit

import java.io.InputStream

/** A response [WebViewClient.shouldInterceptRequest] answers a request with instead of the network. */
open class WebResourceResponse {
    private var mimeType: String?
    private var encoding: String?
    private var statusCode = 200
    private var reasonPhrase: String? = "OK"
    private var responseHeaders: Map<String, String>? = null
    private var data: InputStream?

    constructor(mimeType: String?, encoding: String?, data: InputStream?) {
        this.mimeType = mimeType
        this.encoding = encoding
        this.data = data
    }

    constructor(
        mimeType: String?,
        encoding: String?,
        statusCode: Int,
        reasonPhrase: String,
        responseHeaders: Map<String, String>?,
        data: InputStream?,
    ) {
        this.mimeType = mimeType
        this.encoding = encoding
        this.statusCode = statusCode
        this.reasonPhrase = reasonPhrase
        this.responseHeaders = responseHeaders
        this.data = data
    }

    fun getMimeType() = mimeType
    fun setMimeType(mimeType: String?) { this.mimeType = mimeType }
    fun getEncoding() = encoding
    fun setEncoding(encoding: String?) { this.encoding = encoding }
    fun getStatusCode() = statusCode
    fun getReasonPhrase() = reasonPhrase
    fun setStatusCodeAndReasonPhrase(statusCode: Int, reasonPhrase: String) {
        this.statusCode = statusCode
        this.reasonPhrase = reasonPhrase
    }
    fun getResponseHeaders() = responseHeaders
    fun setResponseHeaders(headers: Map<String, String>?) { responseHeaders = headers }
    fun getData() = data
    fun setData(data: InputStream?) { this.data = data }
}

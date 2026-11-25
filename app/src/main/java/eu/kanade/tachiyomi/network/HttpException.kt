package eu.kanade.tachiyomi.network

import okhttp3.Response

/** HTTP 状态码不是 2xx 时抛出的异常，扩展里会按 code 分支处理。 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")

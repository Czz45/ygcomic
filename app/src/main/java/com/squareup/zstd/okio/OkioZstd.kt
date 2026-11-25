package com.squareup.zstd.okio

import okio.GzipSink
import okio.GzipSource
import okio.Sink
import okio.Source

/**
 * 仅提供类签名，避免 Mihon 扩展在类加载阶段找不到 OkioZstd 而直接闪退。
 *
 * 正常请求已经由 NetworkHelper 固定为 Accept-Encoding: gzip，不会走到网络 zstd 解压；
 * 这里为扩展的本地筛选缓存提供 gzip 等价封装，避免 NoClassDefFoundError / native abort。
 */
object OkioZstd {
    // 扩展把筛选数据缓存到本地时用 zstd 压缩/解压。这里用 gzip 做等价的可逆封装，
    // 两端都在本进程内，读写能对上；网络响应仍固定走 gzip，不触碰 native zstd。
    @JvmStatic
    fun zstdCompress(sink: Sink): Sink = GzipSink(sink)

    @JvmStatic
    fun zstdDecompress(source: Source): Source = GzipSource(source)
}

package com.yg.comic.source.mihon

import dalvik.system.DexClassLoader
import java.io.File

/**
 * 子优先的 DexClassLoader：
 * - `eu.kanade.tachiyomi.*`、`kotlin.*`、`android.*`、okhttp/jsoup 等宿主 API 必须走父加载器，
 *   保证扩展和宿主用的是同一份接口/类；
 * - 其余类（扩展自己的类、keiyoushi.* 工具类）先从扩展 APK 加载，找不到再回落宿主。
 */
class ChildFirstPathClassLoader(
    dexPath: String,
    optimizedDirectory: File,
    parent: ClassLoader,
    private val parentFirstPrefixes: List<String>,
) : DexClassLoader(dexPath, optimizedDirectory.absolutePath, null, parent) {

    private val hostParent: ClassLoader = parent

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (parentFirstPrefixes.any { name.startsWith(it) }) {
            // 必须直接问宿主，不能走 DexClassLoader.loadClass：
            // 它先查扩展 APK 自己的 dex，扩展若没打包 kotlin/okhttp 就会 ClassNotFoundException。
            return try {
                hostParent.loadClass(name)
            } catch (_: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }
        synchronized(this) {
            var clazz = findLoadedClass(name)
            if (clazz == null) {
                clazz = try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    null
                }
                if (clazz == null) {
                    clazz = try {
                        hostParent.loadClass(name)
                    } catch (_: ClassNotFoundException) {
                        super.loadClass(name, resolve)
                    }
                }
            }
            if (resolve) resolveClass(clazz)
            return clazz
        }
    }
}

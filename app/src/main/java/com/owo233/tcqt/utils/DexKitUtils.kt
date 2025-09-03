package com.owo233.tcqt.utils

import com.owo233.tcqt.ext.XpClassLoader
import com.owo233.tcqt.hooks.base.hostInfo
import com.tencent.mmkv.MMKV
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object DexKitUtils {
    private const val CACHE_ID = "TCQT_DexKitCache"
    private const val KEY_CURRENT_HOST_KEY = "current_host_key"

    private val mmkv: MMKV by lazy { MMKVUtils.mmkvWithId(CACHE_ID) }

    private var bridge: DexKitBridge? = null

    private val currentHostKey: String
        get() = "${PlatformTools.getHostName()}_${PlatformTools.getHostVersionCode()}"

    fun initDexkitBridge() {
        if (bridge != null) return

        val lastHostKey = mmkv.getString(KEY_CURRENT_HOST_KEY, null)
        val current = currentHostKey
        if (lastHostKey != current) {
            mmkv.clear()
            mmkv.putString(KEY_CURRENT_HOST_KEY, current)
        }

        val apkPath = hostInfo.application.applicationInfo.sourceDir
        System.loadLibrary("dexkit")
        bridge = DexKitBridge.create(apkPath)
    }

    /**
     * 不可以主动调用
     */
    fun release() {
        bridge?.close()
        bridge = null
    }

    private fun put(key: String, value: String) {
        mmkv.putString("${currentHostKey}_$key", value)
    }

    private fun get(key: String): String? {
        return mmkv.getString("${currentHostKey}_$key", null)
    }

    fun findClass(
        key: String,
        hostClassLoader: ClassLoader = XpClassLoader,
        block: FindClass.() -> Unit
    ): Class<*>? {
        val cacheKey = "class_$key"
        val cached = get(cacheKey)
        if (!cached.isNullOrEmpty()) {
            return runCatching { hostClassLoader.loadClass(cached) }
                .onFailure { logE(msg = "DexKit findClass failed for cached class: $cached", cause = it) }
                .getOrNull()
        }

        val result = bridge?.findClass(block)?.singleOrNull() ?: return null
        val className = result.name
        put(cacheKey, className)
        return runCatching { hostClassLoader.loadClass(className) }
            .onFailure { logE(msg = "DexKit findClass failed for class: $className", cause = it) }
            .getOrNull()
    }

    fun findMethod(
        key: String,
        hostClassLoader: ClassLoader = XpClassLoader,
        block: FindMethod.() -> Unit
    ): Method? {
        val cacheKey = "method_$key"
        val cached = get(cacheKey)
        if (!cached.isNullOrEmpty()) {
            return runCatching { DexMethod.deserialize(cached).getMethodInstance(hostClassLoader) }
                .onFailure { logE(msg = "DexKit findMethod failed for cached method: $key", cause = it) }
                .getOrNull()
        }

        val result = bridge?.findMethod(block)?.singleOrNull() ?: return null
        val dexMethod = result.toDexMethod()
        val serialized = dexMethod.serialize()
        put(cacheKey, serialized)
        return runCatching { dexMethod.getMethodInstance(hostClassLoader) }
            .onFailure { logE(msg = "DexKit findMethod failed for method: $key", cause = it) }
            .getOrNull()
    }

    fun findField(
        key: String,
        hostClassLoader: ClassLoader = XpClassLoader,
        block: FindField.() -> Unit
    ): Field? {
        val cacheKey = "field_$key"
        val cached = get(cacheKey)
        if (!cached.isNullOrEmpty()) {
            return runCatching { DexField.deserialize(cached).getFieldInstance(hostClassLoader) }
                .onFailure { logE(msg = "DexKit findField failed for cached field: $key", cause = it) }
                .getOrNull()
        }

        val result = bridge?.findField(block)?.singleOrNull() ?: return null
        val dexField = result.toDexField()
        val serialized = dexField.serialize()
        put(cacheKey, serialized)
        return runCatching { dexField.getFieldInstance(hostClassLoader) }
            .onFailure { logE(msg = "DexKit findField failed for field: $key", cause = it) }
            .getOrNull()
    }
}

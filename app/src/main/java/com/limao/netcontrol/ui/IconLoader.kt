package com.limao.netcontrol.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 应用图标内存缓存（按总字节数预算，LRU 淘汰），IO 线程解码。 */
object IconLoader {
    /**
     * 图标缓存总字节预算约 12 MiB。
     * 按需解码（默认 96px、ARGB_8888 约 36 KiB/张）可容纳约 340 张；
     * 不再全量预加载 600 张 192px（约 84 MiB）。
     */
    private const val MAX_CACHE_BYTES = 12 * 1024 * 1024
    private const val MIN_PX = 32
    private const val MAX_PX = 256
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {}
    private var cacheBytes = 0L
    /** 限制并发解码数，避免滑动时 IO 拥堵。 */
    private val semaphore = kotlinx.coroutines.sync.Semaphore(4)

    private fun getFromCache(key: String): ImageBitmap? = synchronized(cacheLock) { cache[key] }

    private fun putToCache(key: String, bitmap: ImageBitmap, bytes: Long) =
        synchronized(cacheLock) {
            val old = cache.put(key, bitmap)
            if (old != null) {
                // 同 key 覆盖：先减掉旧图字节（按旧图尺寸估算）
                cacheBytes -= old.width * old.height * 4L
            }
            cacheBytes += bytes
            // 淘汰最久未使用，直到回到预算内（至少保留最新一张）
            while (cacheBytes > MAX_CACHE_BYTES && cache.size > 1) {
                val eldest = cache.entries.iterator().next()
                cache.remove(eldest.key)
                cacheBytes -= eldest.value.width * eldest.value.height * 4L
            }
        }

    /**
     * 按需加载图标。px 真正参与解码（按目标尺寸解码，不再固定 192px），
     * 缓存键包含包名 + 尺寸，不同尺寸分别缓存。
     */
    suspend fun load(
        context: android.content.Context,
        packageName: String,
        px: Int = 96
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val targetPx = px.coerceIn(MIN_PX, MAX_PX)
        val key = "$packageName@$targetPx"
        getFromCache(key)?.let { return@withContext it }
        semaphore.acquire()
        try {
            // double-check after acquiring
            getFromCache(key)?.let { return@withContext it }
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawable.toBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888).asImageBitmap()
            putToCache(key, bitmap, targetPx * targetPx * 4L)
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            semaphore.release()
        }
    }
}

/** 异步加载的应用图标，带占位符。 */
@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val context = LocalContext.current
    // 一、6 优化：按实际屏幕密度计算像素尺寸，高密度屏不再发糊
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = remember(size, density) { with(density) { size.roundToPx() } }
    var bitmap by remember(packageName, px) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, px) {
        bitmap = IconLoader.load(context, packageName, px)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier
                .size(size)
                .background(Color.Gray.copy(alpha = 0.18f), CircleShape)
        )
    }
}

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
import java.util.Collections

/** 应用图标内存缓存（最多 200 个），IO 线程解码。 */
object IconLoader {
    private const val MAX_SIZE = 200
    private val cache: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ImageBitmap>?
            ): Boolean = size > MAX_SIZE
        }
    )

    suspend fun load(
        context: android.content.Context,
        packageName: String,
        px: Int = 96
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$packageName@$px"
        cache[key]?.let { return@withContext it }
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawable.toBitmap(px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
            cache[key] = bitmap
            bitmap
        } catch (_: Exception) {
            null
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

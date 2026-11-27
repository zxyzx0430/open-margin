package com.bookkeeping.app.ui.components

import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import android.util.LruCache
import java.io.File
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import com.bookkeeping.app.ui.theme.LocalQingDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CategoryIcon(
    categoryKey: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val visual = categoryVisual(categoryKey)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(visual.background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.foreground,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

@Composable
fun CategoryIcon(
    category: TransactionCategory,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    cornerRadius: Dp = size * 0.34f,
) {
    val visual = categoryVisual(category)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(visual.background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.foreground,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

private data class CachedMerchantBitmap(val stamp: Long, val bytes: Long, val bitmap: ImageBitmap)
private val merchantBitmapCache = object : LruCache<String, CachedMerchantBitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: CachedMerchantBitmap): Int = value.bitmap.width * value.bitmap.height * 4
}

@Composable
fun MerchantAvatar(
    merchant: String,
    merchantIconKey: String?,
    category: TransactionCategory,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    merchantIconPath: String? = null,
    cornerRadius: Dp = size * 0.34f,
    categoryKey: String? = null,
) {
    val context = LocalContext.current
    val customOverrides by remember(context) {
        com.bookkeeping.app.data.merchant.CustomMerchantIconStore.get(context).overrides
    }.collectAsState()
    val customOverride = remember(merchant, customOverrides) {
        com.bookkeeping.app.data.merchant.CustomMerchantIconStore.resolveFrom(customOverrides, merchant)
    }

    val effectivePath = customOverride?.imageFileName ?: merchantIconPath
    val localIcon = rememberLocalMerchantBitmap(effectivePath)
    if (localIcon != null) {
        Image(
            bitmap = localIcon,
            contentDescription = merchant,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius)),
        )
        return
    }
    val overrideBrandDrawable = remember(customOverride?.brandKey) {
        brandLogoDrawableRes(customOverride?.brandKey)
    }
    if (overrideBrandDrawable != null) {
        Image(
            painter = androidx.compose.ui.res.painterResource(id = overrideBrandDrawable),
            contentDescription = merchant,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius)),
        )
        return
    }
    val spec = remember(merchant, merchantIconKey) { MerchantIconCatalog.forTransaction(merchant, merchantIconKey) }
    if (spec == null) {
        if (!categoryKey.isNullOrBlank()) {
            CategoryIcon(categoryKey = categoryKey, modifier = modifier, size = size)
        } else {
            CategoryIcon(category = category, modifier = modifier, size = size, cornerRadius = cornerRadius)
        }
        return
    }
    val brandDrawable = remember(spec.key) { brandLogoDrawableRes(spec.key) }
    if (brandDrawable != null) {
        Image(
            painter = androidx.compose.ui.res.painterResource(id = brandDrawable),
            contentDescription = merchant,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius)),
        )
        return
    }
    val color = Color(spec.colorArgb)
    val displayColor = if (LocalQingDark.current) {
        lerp(color, Color.White, 0.25f)
    } else {
        color
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(displayColor.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        val icon = merchantSystemIcon(spec.systemIcon)
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = displayColor,
                modifier = Modifier.size(size * 0.46f),
            )
        } else {
            Text(
                text = spec.initials,
                color = displayColor,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun rememberLocalMerchantBitmap(iconPath: String?): ImageBitmap? {
    if (iconPath.isNullOrBlank()) return null
    val filesDir = LocalContext.current.filesDir
    val path = remember(iconPath, filesDir) {
        val file = File(iconPath)
        if (file.isAbsolute) file.absolutePath
        else if (file.name == iconPath) File(File(filesDir, "merchant_icons"), file.name).absolutePath
        else null
    } ?: return null
    val loaded by androidx.compose.runtime.key(path) {
        produceState<ImageBitmap?>(merchantBitmapCache.get(path)?.bitmap, path) {
            value = withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.isFile) {
                    merchantBitmapCache.remove(path)
                    null
                } else {
                    val stamp = file.lastModified()
                    val bytes = file.length()
                    val cached = merchantBitmapCache.get(path)
                    if (cached != null && cached.stamp == stamp && cached.bytes == bytes) {
                        cached.bitmap
                    } else {
                        runCatching {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(path, bounds)
                            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                            val sample = if (maxDim > 384) 2 else 1
                            val options = BitmapFactory.Options().apply { inSampleSize = sample }
                            BitmapFactory.decodeFile(path, options)?.asImageBitmap()?.also {
                                merchantBitmapCache.put(path, CachedMerchantBitmap(stamp, bytes, it))
                            }
                        }.getOrNull()
                    }
                }
            }
        }
    }
    return loaded
}

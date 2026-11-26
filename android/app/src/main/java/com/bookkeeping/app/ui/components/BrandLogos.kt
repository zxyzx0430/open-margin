package com.bookkeeping.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Crisp zero-dependency brand mark vectors keyed by [MerchantIconCatalog] rule key.
 * Falls back to [merchantSystemIcon] for general categories.
 */
fun brandLogoVector(key: String?): ImageVector? = when (key) {
    "chatgpt" -> ChatGptVector
    "youtube" -> YouTubeVector
    "netflix" -> NetflixVector
    "spotify" -> SpotifyVector
    "icloud" -> CloudMarkVector
    "disney" -> DisneyVector
    "bilibili" -> BilibiliVector
    "claude" -> ClaudeVector
    "gemini" -> GeminiVector
    "github" -> GitHubVector
    "coding_ai" -> CursorVector
    else -> null
}

private val ChatGptVector: ImageVector by lazy {
    ImageVector.Builder("BrandChatGPT", 24.dp, 24.dp, 24f, 24f).apply {
        val stroke = SolidColor(Color.Black)
        // 6-petal OpenAI-style geometric knot
        path(stroke = stroke, strokeLineWidth = 1.65f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 3.2f)
            lineTo(16.2f, 5.6f)
            lineTo(16.2f, 10.4f)
            moveTo(16.2f, 5.6f)
            lineTo(19.6f, 7.6f)
            lineTo(19.6f, 12.4f)
            lineTo(15.4f, 14.8f)
            moveTo(19.6f, 12.4f)
            lineTo(19.6f, 16.4f)
            lineTo(15.4f, 18.8f)
            lineTo(11.2f, 16.4f)
            moveTo(15.4f, 18.8f)
            lineTo(12f, 20.8f)
            lineTo(7.8f, 18.4f)
            lineTo(7.8f, 13.6f)
            moveTo(7.8f, 18.4f)
            lineTo(4.4f, 16.4f)
            lineTo(4.4f, 11.6f)
            lineTo(8.6f, 9.2f)
            moveTo(4.4f, 11.6f)
            lineTo(4.4f, 7.6f)
            lineTo(8.6f, 5.2f)
            lineTo(12.8f, 7.6f)
            moveTo(8.6f, 5.2f)
            lineTo(12f, 3.2f)
        }
        path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 9.2f)
            lineTo(14.4f, 10.6f)
            lineTo(14.4f, 13.4f)
            lineTo(12f, 14.8f)
            lineTo(9.6f, 13.4f)
            lineTo(9.6f, 10.6f)
            close()
        }
    }.build()
}

private val YouTubeVector: ImageVector by lazy {
    ImageVector.Builder("BrandYouTube", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // Rounded screen rectangle
            moveTo(4.5f, 6.2f)
            curveTo(3.1f, 6.4f, 2.2f, 7.5f, 2.1f, 9f)
            curveTo(2f, 11f, 2f, 13f, 2.1f, 15f)
            curveTo(2.2f, 16.5f, 3.1f, 17.6f, 4.5f, 17.8f)
            curveTo(9.5f, 18.2f, 14.5f, 18.2f, 19.5f, 17.8f)
            curveTo(20.9f, 17.6f, 21.8f, 16.5f, 21.9f, 15f)
            curveTo(22f, 13f, 22f, 11f, 21.9f, 9f)
            curveTo(21.8f, 7.5f, 20.9f, 6.4f, 19.5f, 6.2f)
            curveTo(14.5f, 5.8f, 9.5f, 5.8f, 4.5f, 6.2f)
            close()
            // Inner play triangle cutout
            moveTo(10f, 9.2f)
            lineTo(15.6f, 12f)
            lineTo(10f, 14.8f)
            close()
        }
    }.build()
}

private val NetflixVector: ImageVector by lazy {
    ImageVector.Builder("BrandNetflix", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6.5f, 4f)
            lineTo(9.8f, 4f)
            lineTo(14.2f, 15.6f)
            lineTo(14.2f, 4f)
            lineTo(17.5f, 4f)
            lineTo(17.5f, 20f)
            lineTo(14.2f, 20f)
            lineTo(9.8f, 8.4f)
            lineTo(9.8f, 20f)
            lineTo(6.5f, 20f)
            close()
        }
    }.build()
}

private val SpotifyVector: ImageVector by lazy {
    ImageVector.Builder("BrandSpotify", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.1f, strokeLineCap = StrokeCap.Round) {
            moveTo(5.5f, 8.8f)
            quadTo(12.2f, 6.6f, 18.8f, 9.4f)
            moveTo(6.6f, 12.6f)
            quadTo(12.2f, 10.8f, 17.6f, 13.1f)
            moveTo(7.8f, 16.2f)
            quadTo(12.1f, 14.9f, 16.3f, 16.7f)
        }
    }.build()
}

private val CloudMarkVector: ImageVector by lazy {
    ImageVector.Builder("BrandCloud", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.2f, 18.2f)
            horizontalLineTo(17.4f)
            curveTo(19.9f, 18.2f, 21.6f, 16.4f, 21.6f, 14.1f)
            curveTo(21.6f, 12.1f, 20.2f, 10.4f, 18.3f, 10.1f)
            curveTo(17.7f, 6.9f, 14.9f, 4.8f, 11.7f, 4.8f)
            curveTo(8.3f, 4.8f, 5.6f, 7.3f, 5.2f, 10.5f)
            curveTo(3.4f, 11.0f, 2.2f, 12.6f, 2.2f, 14.5f)
            curveTo(2.2f, 16.6f, 4.0f, 18.2f, 7.2f, 18.2f)
            close()
        }
    }.build()
}

private val DisneyVector: ImageVector by lazy {
    ImageVector.Builder("BrandDisney", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round) {
            moveTo(4.2f, 11.5f)
            quadTo(12f, 4.2f, 19.8f, 11.5f)
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8.2f, 12.5f)
            lineTo(8.2f, 19.2f)
            moveTo(6.5f, 13.8f)
            curveTo(11.8f, 12.2f, 15.2f, 14.5f, 14.5f, 17.5f)
            curveTo(13.9f, 19.4f, 10.2f, 19.8f, 6.8f, 18.5f)
            moveTo(17.5f, 14.2f)
            lineTo(17.5f, 18.2f)
            moveTo(15.5f, 16.2f)
            lineTo(19.5f, 16.2f)
        }
    }.build()
}

private val BilibiliVector: ImageVector by lazy {
    ImageVector.Builder("BrandBilibili", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7.5f, 4.2f)
            lineTo(10.2f, 6.8f)
            moveTo(16.5f, 4.2f)
            lineTo(13.8f, 6.8f)
            moveTo(5.4f, 7.2f)
            horizontalLineTo(18.6f)
            curveTo(19.8f, 7.2f, 20.6f, 8.0f, 20.6f, 9.2f)
            verticalLineTo(17.4f)
            curveTo(20.6f, 18.6f, 19.8f, 19.4f, 18.6f, 19.4f)
            horizontalLineTo(5.4f)
            curveTo(4.2f, 19.4f, 3.4f, 18.6f, 3.4f, 17.4f)
            verticalLineTo(9.2f)
            curveTo(3.4f, 8.0f, 4.2f, 7.2f, 5.4f, 7.2f)
            close()
            moveTo(8.2f, 12.2f)
            lineTo(9.8f, 13.4f)
            moveTo(15.8f, 12.2f)
            lineTo(14.2f, 13.4f)
        }
    }.build()
}

private val ClaudeVector: ImageVector by lazy {
    ImageVector.Builder("BrandClaude", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.0f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 3.5f); lineTo(12f, 20.5f)
            moveTo(3.5f, 12f); lineTo(20.5f, 12f)
            moveTo(6.0f, 6.0f); lineTo(18.0f, 18.0f)
            moveTo(18.0f, 6.0f); lineTo(6.0f, 18.0f)
        }
    }.build()
}

private val GeminiVector: ImageVector by lazy {
    ImageVector.Builder("BrandGemini", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2.5f)
            quadraticTo(13.2f, 10.8f, 21.5f, 12f)
            quadraticTo(13.2f, 13.2f, 12f, 21.5f)
            quadraticTo(10.8f, 13.2f, 2.5f, 12f)
            quadraticTo(10.8f, 10.8f, 12f, 2.5f)
            close()
        }
    }.build()
}

private val GitHubVector: ImageVector by lazy {
    ImageVector.Builder("BrandGitHub", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.85f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8.5f, 17.5f)
            lineTo(4.0f, 12.0f)
            lineTo(8.5f, 6.5f)
            moveTo(15.5f, 6.5f)
            lineTo(20.0f, 12.0f)
            lineTo(15.5f, 17.5f)
            moveTo(13.2f, 5.2f)
            lineTo(10.8f, 18.8f)
        }
    }.build()
}

private val CursorVector: ImageVector by lazy {
    ImageVector.Builder("BrandCursor", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5.2f, 4.0f)
            lineTo(19.5f, 11.4f)
            lineTo(13.1f, 13.4f)
            lineTo(10.8f, 19.8f)
            close()
        }
    }.build()
}

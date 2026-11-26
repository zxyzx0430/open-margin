package com.bookkeeping.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import com.bookkeeping.app.data.merchant.MerchantSuggestion
import com.bookkeeping.app.ui.theme.qingColors

/**
 * Real-time keyword suggestion strip backed by [MerchantIconCatalog].
 * Appears once the user types >= 1 character and hides after an exact match is picked.
 */
@Composable
fun MerchantSuggestionBar(
    query: String,
    onSelect: (MerchantSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = remember(query) {
        MerchantIconCatalog.suggestMerchants(query = query, limit = 5)
    }
    AnimatedVisibility(
        visible = suggestions.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            items(suggestions, key = { it.displayName }) { item ->
                MerchantSuggestionChip(
                    suggestion = item,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

@Composable
private fun MerchantSuggestionChip(
    suggestion: MerchantSuggestion,
    onClick: () -> Unit,
) {
    val colors = qingColors
    val brandColor = Color(suggestion.spec.colorArgb)
    val vector = remember(suggestion.spec.key, suggestion.spec.systemIcon) {
        brandLogoVector(suggestion.spec.key) ?: merchantSystemIcon(suggestion.spec.systemIcon)
    }
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = colors.surfaceElevated,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(brandColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (vector != null) {
                    Icon(
                        imageVector = vector,
                        contentDescription = null,
                        tint = brandColor,
                        modifier = Modifier.size(13.dp),
                    )
                } else {
                    Text(
                        text = suggestion.spec.initials,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = brandColor,
                    )
                }
            }
            Text(
                text = suggestion.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

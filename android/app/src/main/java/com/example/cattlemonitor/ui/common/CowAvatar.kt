package com.example.cattlemonitor.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.ui.theme.Brand

/**
 * Cow avatar (#photo feature): shows the user's uploaded photo when present,
 * otherwise the ear-tag chip motif with the cow's initial. Reused on the
 * herd card and the detail header. [onClick] makes it an "add/change photo"
 * affordance on the detail screen.
 */
@Composable
fun CowAvatar(
    cow: Cow?,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val base = modifier
        .size(size)
        .clip(shape)
        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)

    val url = cow?.imageUrl
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = cow?.name,
            contentScale = ContentScale.Crop,
            modifier = base,
        )
    } else {
        Box(
            modifier = base.background(Brand),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (cow?.name ?: "?").take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

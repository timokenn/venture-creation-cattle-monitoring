package com.example.cattlemonitor.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.ui.theme.Bg
import com.example.cattlemonitor.ui.theme.Ink
import com.example.cattlemonitor.ui.theme.InkSoft

/**
 * Slim banner for the HYBRID offline mode (#9): last-known data stays on
 * screen (it is already in the view-model state) while a quiet bar explains
 * why nothing is moving. Disappears by itself when connectivity returns.
 */
@Composable
fun OfflineBanner() {
    val isOnline by ServiceLocator.connectivity.isOnline.collectAsState()
    if (isOnline) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_offline),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            stringResource(R.string.offline_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onError.copy(alpha = 0.9f),
        )
    }
}

/**
 * Full "You're offline" state for screens with nothing to show yet. When the
 * device comes back online, [onBackOnline] fires once and the host screen
 * (whose live-query flows are still retrying underneath) simply swaps this
 * out for real content — no manual refresh needed.
 */
@Composable
fun OfflineScreen(onBackOnline: () -> Unit = {}) {
    val isOnline by ServiceLocator.connectivity.isOnline.collectAsState()

    LaunchedEffect(isOnline) {
        if (isOnline) onBackOnline()
    }

    if (!isOnline) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .background(InkSoft.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_offline),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.offline_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.offline_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.offline_reconnect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

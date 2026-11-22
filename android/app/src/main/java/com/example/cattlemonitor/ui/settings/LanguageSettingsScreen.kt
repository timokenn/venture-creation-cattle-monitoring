package com.example.cattlemonitor.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.ui.common.AppCard
import com.example.cattlemonitor.ui.common.ScreenHeader
import com.example.cattlemonitor.ui.common.ScreenPadding
import com.example.cattlemonitor.ui.theme.Accent
import kotlinx.coroutines.launch

/**
 * The "settings" nav destination: account, language, appearance and
 * notification settings, each in its own card.
 */
@Composable
fun SettingsScreenWithLanguage(onLoggedOut: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = stringResource(R.string.settings_title))
        Column(
            modifier = Modifier.padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileSection(onLoggedOut)
            LanguageSection()
            ThemeSection()
            NotificationsSection()
        }
        BrandFooter()
    }
}

@Composable
private fun ProfileSection(onLoggedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val email = ServiceLocator.auth.userEmail
        ?: stringResource(R.string.profile_signed_in)

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    email.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    stringResource(R.string.profile_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    email,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = {
                    loading = true
                    scope.launch {
                        ServiceLocator.auth.signOut()
                        loading = false
                        onLoggedOut()
                    }
                },
                enabled = !loading,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            ) {
                Text(if (loading) "…" else stringResource(R.string.profile_logout))
            }
        }
    }
}

@Composable
private fun LanguageSection() {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.language_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            LanguagePickerButton()
        }
    }
}

/** Faint logo + tagline at the very bottom of Settings. */
@Composable
private fun BrandFooter() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.decow_logo),
            contentDescription = null,
            modifier = Modifier.width(96.dp).alpha(0.25f),
        )
        Text(
            stringResource(R.string.settings_brand_tagline),
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

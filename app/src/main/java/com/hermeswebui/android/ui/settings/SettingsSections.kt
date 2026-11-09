package com.hermeswebui.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.webapp.GatewayAlertKind
import com.hermeswebui.android.webapp.WebappBackgroundSettings

@Composable
internal fun UpdatesSettingsSection(
    appUpdateAlertsEnabled: Boolean,
    automaticAppUpdateChecksEnabled: Boolean,
    appUpdateChannelLabel: String,
    appUpdateStatus: String?,
    appUpdateReleaseUrl: String?,
    appUpdateDownloadUrl: String?,
    appUpdateInstallReady: Boolean,
    appUpdateReleaseNotes: String?,
    onSetAppUpdateAlertsEnabled: (Boolean) -> Unit,
    onSetAutomaticAppUpdateChecksEnabled: (Boolean) -> Unit,
    onCheckAppUpdates: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onOpenAppUpdateRelease: () -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    SectionHeader("Updates")
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .fillMaxWidth()
    ) {
        Column {
            ListItem(
                headlineContent = {
                    Text("App update alerts", color = onSurface, fontWeight = FontWeight.Medium)
                },
                supportingContent = {
                    Text(
                        "Check $appUpdateChannelLabel and notify when an update is available.",
                        color = onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                trailingContent = {
                    Switch(
                        checked = appUpdateAlertsEnabled,
                        onCheckedChange = onSetAppUpdateAlertsEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = primaryColor,
                            uncheckedThumbColor = onSurfaceVariant,
                            uncheckedTrackColor = surfaceVariant
                        )
                    )
                },
                colors = ListItemDefaults.colors(containerColor = surfaceColor),
                modifier = Modifier.clickable {
                    onSetAppUpdateAlertsEnabled(!appUpdateAlertsEnabled)
                }
            )

            HorizontalDivider(color = outlineVariant.copy(alpha = 0.5f))

            ListItem(
                headlineContent = {
                    Text("Automatic checks", color = onSurface, fontWeight = FontWeight.Medium)
                },
                supportingContent = {
                    Text(
                        "Check for updates whenever Hermes opens.",
                        color = onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                trailingContent = {
                    Switch(
                        checked = automaticAppUpdateChecksEnabled,
                        onCheckedChange = onSetAutomaticAppUpdateChecksEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = primaryColor,
                            uncheckedThumbColor = onSurfaceVariant,
                            uncheckedTrackColor = surfaceVariant
                        )
                    )
                },
                colors = ListItemDefaults.colors(containerColor = surfaceColor),
                modifier = Modifier.clickable {
                    onSetAutomaticAppUpdateChecksEnabled(!automaticAppUpdateChecksEnabled)
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isPlayUpdateReady = appUpdateReleaseUrl?.startsWith("play://") == true
                val primaryActionLabel = when {
                    appUpdateInstallReady -> "Install APK"
                    !appUpdateDownloadUrl.isNullOrBlank() -> "Download APK"
                    isPlayUpdateReady -> "Update now"
                    else -> "Check for updates"
                }
                val primaryAction: () -> Unit = when {
                    appUpdateInstallReady || !appUpdateDownloadUrl.isNullOrBlank() -> onDownloadAppUpdate
                    isPlayUpdateReady -> onOpenAppUpdateRelease
                    else -> onCheckAppUpdates
                }

                if (appUpdateInstallReady || !appUpdateDownloadUrl.isNullOrBlank() || isPlayUpdateReady) {
                    Button(
                        onClick = primaryAction,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (appUpdateInstallReady) Color(0xFFC62828) else Color(0xFF2E7D32),
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(primaryActionLabel)
                    }
                } else {
                    OutlinedButton(onClick = primaryAction, modifier = Modifier.fillMaxWidth()) {
                        Text(primaryActionLabel)
                    }
                }

                if (!appUpdateStatus.isNullOrBlank()) {
                    Text(
                        text = appUpdateStatus,
                        color = onSurfaceVariant.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (!appUpdateReleaseNotes.isNullOrBlank()) {
                    Text(
                        text = "What's changed",
                        color = onSurface,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = appUpdateReleaseNotes,
                        color = onSurfaceVariant.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (!appUpdateReleaseUrl.isNullOrBlank() && !appUpdateReleaseUrl.startsWith("play://")) {
                    OutlinedButton(onClick = onOpenAppUpdateRelease, modifier = Modifier.fillMaxWidth()) {
                        Text("Release notes")
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdvancedSettingsSection(
    clientCertificateConfigured: Boolean,
    onOpenClientCertificate: () -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    SectionHeader("Advanced")
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text("Client certificate", fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(
                    if (clientCertificateConfigured) {
                        "Configured for mutual TLS"
                    } else {
                        "Not configured"
                    },
                    color = onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = surfaceColor),
            modifier = Modifier.clickable(onClick = onOpenClientCertificate)
        )
    }
}

@Composable
internal fun AboutSettingsSection(appVersionLabel: String, appUpdateChannelLabel: String) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    SectionHeader("About")
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text("Hermes WebUI", fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(
                    "$appVersionLabel - Updates via $appUpdateChannelLabel",
                    color = onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            colors = ListItemDefaults.colors(containerColor = surfaceColor)
        )
    }
}
/**
 * Official Webapp background settings. The alert rows and their Android channels both come from
 * [GatewayAlertKind], so a new kind appears here without another edit.
 */
@Composable
internal fun WebappNotificationsSection(
    settings: WebappBackgroundSettings,
    onChange: (WebappBackgroundSettings) -> Unit,
    onOpenChannel: (String?) -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    SectionHeader("Notifications")
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceColor)
            .fillMaxWidth()
    ) {
        Column {
            WebappSwitchRow(
                title = "Stay connected in the background",
                summary = "While the official Hermes Agent Webapp is open, keep its connection alive when you switch apps so progress is there when you return. Android shows a quiet ongoing notification.",
                checked = settings.keepAlive,
                onCheckedChange = { onChange(settings.copy(keepAlive = it)) }
            )
            HorizontalDivider(color = outlineVariant.copy(alpha = 0.5f))
            WebappSwitchRow(
                title = "Show message previews",
                summary = "Include reply text, commands and questions in alerts. The lock screen always hides them.",
                checked = settings.showPreview,
                onCheckedChange = { onChange(settings.copy(showPreview = it)) }
            )
            GatewayAlertKind.entries.forEach { kind ->
                HorizontalDivider(color = outlineVariant.copy(alpha = 0.5f))
                WebappSwitchRow(
                    title = kind.label,
                    summary = kind.summary,
                    checked = settings.isEnabled(kind),
                    onCheckedChange = { enabled ->
                        onChange(settings.copy(disabled = if (enabled) settings.disabled - kind else settings.disabled + kind))
                    },
                    onOpenChannel = { onOpenChannel(kind.channelId) }
                )
            }
            HorizontalDivider(color = outlineVariant.copy(alpha = 0.5f))
            ListItem(
                headlineContent = { Text("Android notification settings", fontWeight = FontWeight.Medium) },
                supportingContent = {
                    Text(
                        "Allow notifications, or change sound and vibration for each kind of alert.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = surfaceColor),
                modifier = Modifier.clickable { onOpenChannel(null) }
            )
        }
    }
}

@Composable
private fun WebappSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenChannel: (() -> Unit)? = null
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(summary, color = onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpenChannel != null) {
                    IconButton(onClick = onOpenChannel) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Sound and vibration for $title")
                    }
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = surfaceColor),
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

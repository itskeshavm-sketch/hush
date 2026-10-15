package dev.hush.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hush.chat.ui.components.SectionHeader
import dev.hush.chat.ui.theme.HushAccent
import dev.hush.chat.ui.theme.HushDanger
import dev.hush.chat.ui.theme.HushOnline
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary
import dev.hush.chat.ui.theme.HushWarning

/**
 * Settings.
 *
 * Two settings exist, both of which matter: who you appear as, and which relay
 * you connect to. Everything else in HUSH is either non-configurable on purpose
 * (message retention is zero, always) or shown as a read-only fact (the relay's
 * pinned fingerprint).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    displayName: String,
    relayUrl: String,
    relayFingerprint: String?,
    onDisplayNameChange: (String) -> Unit,
    onRelayUrlChange: (String) -> Unit,
    onTrustNewCertificate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(displayName) { mutableStateOf(displayName) }
    var relay by remember(relayUrl) { mutableStateOf(relayUrl) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("You")
            SettingsField(
                value = name,
                onValueChange = { name = it.take(32) },
                label = "Display name",
                icon = { Icon(Icons.Filled.Person, contentDescription = null, tint = HushTextTertiary) },
                onCommit = { if (it.isNotBlank() && it != displayName) onDisplayNameChange(it) },
            )
            FactRow2(
                "Anyone can pick this name. It is a label, not an identity — compare safety numbers, not names.",
            )

            SectionHeader("Relay")
            SettingsField(
                value = relay,
                onValueChange = { relay = it },
                label = "wss://…/ws",
                icon = { Icon(Icons.Filled.Language, contentDescription = null, tint = HushTextTertiary) },
                keyboardType = KeyboardType.Uri,
                onCommit = { if (it != relayUrl) onRelayUrlChange(it) },
            )

            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(HushSurfaceElevated.copy(alpha = 0.4f))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = null,
                        tint = if (relayFingerprint != null) HushOnline else HushWarning,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Pinned certificate",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                Spacer(Modifier.height(10.dp))

                if (relayFingerprint == null) {
                    Text(
                        text = "No relay connected yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HushTextTertiary,
                    )
                } else {
                    Text(
                        text = "The first certificate this relay presented was recorded and is checked on every connection. A changed certificate stops the app rather than connecting silently.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HushTextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = formatFingerprintForDisplay(relayFingerprint),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = HushTextTertiary,
                        lineHeight = 16.sp,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Replace this relay's certificate",
                style = MaterialTheme.typography.labelMedium,
                color = HushTextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Only do this if you are deliberately re-issuing the relay's certificate. Compare the old and new fingerprints with whoever runs the relay first.",
                style = MaterialTheme.typography.bodySmall,
                color = HushTextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            SectionHeader("What this app does not do")
            NoList(listOf(
                "Store messages on a server — there is no server-side history to store them in",
                "Collect an email address, a phone number, or any account",
                "Upload an avatar, which would be a permanent identifier for an anonymous user",
                "Allow editing a message after it is sent, because there is no edit path in the codebase",
                "Send a search query anywhere — search runs on this device",
            ))

            Spacer(Modifier.height(32.dp))

            Text(
                text = "HUSH is open source. The relay runs about 400 lines of Node and the client is Kotlin + Compose.",
                style = MaterialTheme.typography.bodySmall,
                color = HushTextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: @Composable () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    onCommit: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        label = { Text(label, color = HushTextTertiary) },
        singleLine = true,
        leadingIcon = icon,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = HushAccent,
            unfocusedBorderColor = HushSurfaceElevated,
        ),
    )
}

@Composable
private fun FactRow2(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = HushTextTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun NoList(items: List<String>) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            Row {
                Text(
                    text = "—",
                    color = HushTextTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HushTextSecondary,
                )
            }
        }
    }
}

private fun formatFingerprintForDisplay(hex: String): String =
    hex.replace(":", "")
        .chunked(4)
        .joinToString(" ")
        .chunked(20)
        .joinToString("\n")

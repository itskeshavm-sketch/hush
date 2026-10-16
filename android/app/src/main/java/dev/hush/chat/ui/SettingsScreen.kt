package dev.hush.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
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
import dev.hush.chat.ui.theme.HushThemeCatalog
import dev.hush.chat.ui.theme.HushThemeKeys
import dev.hush.chat.ui.theme.HushDanger
import dev.hush.chat.ui.theme.HushOnline
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary
import dev.hush.chat.ui.theme.HushWarning

/**
 * Settings.
 *
 * Who you appear as, how the app looks, and the connection's pinned
 * certificate. Everything else in HUSH is either non-configurable on purpose
 * (message retention is zero, always) or shown as a read-only fact.
 *
 * There is deliberately no relay address field here. Every install uses the
 * same public relay, and a server address a user can mistype is a support
 * ticket that never needed to exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    displayName: String,
    themeKey: String,
    pinSet: Boolean,
    duressSet: Boolean,
    relayFingerprint: String?,
    onDisplayNameChange: (String) -> Unit,
    onThemeChange: (String) -> Unit,
    onSetAppPin: (String) -> Boolean,
    onSetDuressPin: (String) -> Boolean,
    onVerifyPin: (String) -> Boolean,
    onRemoveLock: (String) -> Boolean,
    onLockNow: () -> Unit,
    onTrustNewCertificate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(displayName) { mutableStateOf(displayName) }
    var pinStep by remember(pinSet) { mutableStateOf(PinStep.NONE) }
    var pinFirst by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

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

            SectionHeader("Appearance")
            ThemePicker(
                selectedKey = themeKey,
                onSelect = onThemeChange,
            )

            Spacer(Modifier.height(14.dp))

            SectionHeader("Privacy")
            PrivacyRows(
                pinSet = pinSet,
                duressSet = duressSet,
                onAction = { pinStep = it; pinFirst = ""; pinError = null },
                onLockNow = onLockNow,
            )
            FactRow2(
                "Disappearing timers: your timer applies to messages you send. " +
                    "Messages you receive follow the sender's timer — yours being " +
                    "off never keeps someone else's timed message.",
            )

            Spacer(Modifier.height(14.dp))

            SectionHeader("Connection")

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

    if (pinStep != PinStep.NONE) {
        PinStepDialog(
            step = pinStep,
            onConfirm = { pin ->
                when (pinStep) {
                    PinStep.NEW_APP -> {
                        if (!validPin(pin)) {
                            pinError = "Use 4 to 12 digits."
                        } else {
                            pinFirst = pin
                            pinStep = PinStep.CONFIRM_APP
                            pinError = null
                        }
                    }
                    PinStep.CONFIRM_APP -> {
                        if (pin != pinFirst) {
                            pinError = "Those don't match. Start over."
                            pinStep = PinStep.NEW_APP
                        } else if (onSetAppPin(pin)) {
                            pinStep = PinStep.NONE
                        } else {
                            pinError = "That PIN won't work. Try another."
                        }
                    }
                    PinStep.VERIFY -> {
                        if (onVerifyPin(pin)) {
                            pinStep = PinStep.NEW_APP
                            pinError = null
                        } else {
                            pinError = "Wrong PIN."
                        }
                    }
                    PinStep.NEW_DURESS -> {
                        if (!validPin(pin)) {
                            pinError = "Use 4 to 12 digits."
                        } else {
                            pinFirst = pin
                            pinStep = PinStep.CONFIRM_DURESS
                            pinError = null
                        }
                    }
                    PinStep.CONFIRM_DURESS -> {
                        if (pin != pinFirst) {
                            pinError = "Those don't match. Start over."
                            pinStep = PinStep.NEW_DURESS
                        } else if (onSetDuressPin(pin)) {
                            pinStep = PinStep.NONE
                        } else {
                            pinError = "Must differ from the app PIN."
                            pinStep = PinStep.NEW_DURESS
                        }
                    }
                    PinStep.VERIFY_REMOVE -> {
                        if (onRemoveLock(pin)) {
                            pinStep = PinStep.NONE
                        } else {
                            pinError = "Wrong PIN."
                        }
                    }
                    PinStep.NONE -> Unit
                }
            },
            error = pinError,
            onDismiss = { pinStep = PinStep.NONE; pinError = null },
        )
    }
}

private enum class PinStep {
    NONE, NEW_APP, CONFIRM_APP, VERIFY, NEW_DURESS, CONFIRM_DURESS, VERIFY_REMOVE,
}

private fun validPin(pin: String): Boolean =
    pin.length in 4..12 && pin.all { it.isDigit() }

/**
 * The lock rows. When no PIN exists there is one row; once set, the section
 * grows change / duress / remove / lock-now rows. The duress row names the
 * consequence outright — a wipe PIN whose effect is a surprise is a Deleted
 * Data mystery, not a feature.
 */
@Composable
private fun PrivacyRows(
    pinSet: Boolean,
    duressSet: Boolean,
    onAction: (PinStep) -> Unit,
    onLockNow: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!pinSet) {
            PrivacyRow(
                title = "Set app PIN",
                body = "Asked every time the app opens. Separate from the phone lock.",
                onClick = { onAction(PinStep.NEW_APP) },
            )
        } else {
            PrivacyRow(
                title = "Change app PIN",
                body = "Needs the current PIN first.",
                onClick = { onAction(PinStep.VERIFY) },
            )
            PrivacyRow(
                title = if (duressSet) "Change erase-data PIN" else "Set erase-data PIN",
                body = if (duressSet) {
                    "Entering it on the lock screen still erases everything."
                } else {
                    "A second PIN that erases everything when entered on the lock screen."
                },
                onClick = { onAction(PinStep.NEW_DURESS) },
            )
            PrivacyRow(title = "Lock now", body = null, onClick = onLockNow)
            PrivacyRow(
                title = "Remove lock",
                body = "Needs the current PIN.",
                onClick = { onAction(PinStep.VERIFY_REMOVE) },
                danger = true,
            )
        }
    }
}

@Composable
private fun PrivacyRow(
    title: String,
    body: String?,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HushSurfaceElevated.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) HushDanger else MaterialTheme.colorScheme.onBackground,
            )
            body?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = HushTextTertiary,
                )
            }
        }
    }
}

@Composable
private fun PinStepDialog(
    step: PinStep,
    onConfirm: (String) -> Unit,
    error: String?,
    onDismiss: () -> Unit,
) {
    var pin by remember(step) { mutableStateOf("") }
    val (title, confirm) = when (step) {
        PinStep.NEW_APP -> "Choose an app PIN" to "Continue"
        PinStep.CONFIRM_APP -> "Repeat the PIN" to "Save PIN"
        PinStep.VERIFY -> "Enter the current PIN" to "Continue"
        PinStep.NEW_DURESS -> "Choose an erase-data PIN" to "Continue"
        PinStep.CONFIRM_DURESS -> "Repeat the erase-data PIN" to "Save"
        PinStep.VERIFY_REMOVE -> "Enter the current PIN" to "Remove lock"
        PinStep.NONE -> "" to ""
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == PinStep.NEW_DURESS) {
                    Text(
                        text = "Entering this PIN on the lock screen erases chats, identity, and settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HushTextSecondary,
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HushAccent,
                        unfocusedBorderColor = HushSurfaceElevated,
                    ),
                )
                error?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = HushDanger)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(pin) }) {
                Text(confirm)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * One row per theme: three palette dots, the name, one line of character, and
 * a check when selected. Tapping repaints the whole app instantly, because
 * every screen reads colour through the same palette.
 */
@Composable
private fun ThemePicker(
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HushThemeCatalog.forEach { palette ->
            val selected = palette.key == selectedKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (selected) HushAccent.copy(alpha = 0.12f)
                        else HushSurfaceElevated.copy(alpha = 0.4f),
                    )
                    .border(
                        BorderStroke(
                            if (selected) 1.5.dp else 0.dp,
                            if (selected) HushAccent else HushSurfaceElevated,
                        ),
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { onSelect(palette.key) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row {
                    listOf(palette.background, palette.accent, palette.textPrimary).forEachIndexed { i, dot ->
                        Box(
                            modifier = Modifier
                                .offset(x = (-8 * i).dp)
                                .size(24.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(dot)
                                .border(
                                    BorderStroke(1.5.dp, HushSurfaceElevated),
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ThemeNames[palette.key] ?: palette.key,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = ThemeNotes[palette.key] ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = HushTextTertiary,
                    )
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(HushAccent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.background,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

private val ThemeNames = mapOf(
    HushThemeKeys.LAMP to "Lamp Room",
    HushThemeKeys.REDACTED to "Redacted File",
    HushThemeKeys.SPEAKEASY to "Speakeasy",
    HushThemeKeys.CLEAN to "Clean Room",
    HushThemeKeys.NEON to "Neon Sign",
)

private val ThemeNotes = mapOf(
    HushThemeKeys.LAMP to "Lamp amber over night water",
    HushThemeKeys.REDACTED to "Stamp red, redaction bars",
    HushThemeKeys.SPEAKEASY to "Brass fittings, oxblood seal",
    HushThemeKeys.CLEAN to "True white, Klein blue",
    HushThemeKeys.NEON to "Tube pink on real black",
)

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

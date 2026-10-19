package dev.hush.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hush.chat.R
import dev.hush.chat.ui.components.clickableNoRipple
import dev.hush.chat.ui.theme.HushAccent
import dev.hush.chat.ui.theme.HushOnline
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary

/**
 * Onboarding.
 *
 * One field. The relay is a piece of infrastructure the person using this app
 * should not have to know exists — they pick a name, and it connects.
 *
 * The relay field is still here, behind "Advanced", because self-hosting is a
 * real thing people want and the answer must not be "edit the APK". But it is
 * collapsed, unlabelled with jargon, and off by default. The previous version
 * showed it as a required field with a placeholder that resolved nowhere,
 * which meant every new install opened onto a dead connection screen.
 */
@Composable
fun OnboardingScreen(
    initialRelay: String,
    onContinue: (name: String, relayUrl: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var relay by remember { mutableStateOf(initialRelay) }
    var showAdvanced by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val submit = {
        keyboard?.hide()
        onContinue(name, relay)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // A soft accent wash so the first screen does not open as a flat void.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(HushAccent.copy(alpha = 0.16f), androidx.compose.ui.graphics.Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(72.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(HushAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 2.sp,
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = HushTextSecondary,
            )

            Spacer(Modifier.height(44.dp))

            Text(
                text = stringResource(R.string.onboarding_name_label),
                style = MaterialTheme.typography.labelMedium,
                color = HushTextTertiary,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 32) name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                placeholder = { Text("Alex", color = HushTextTertiary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HushAccent,
                    unfocusedBorderColor = HushSurfaceElevated,
                    focusedContainerColor = HushSurfaceElevated.copy(alpha = 0.4f),
                    unfocusedContainerColor = HushSurfaceElevated.copy(alpha = 0.25f),
                ),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_name_hint),
                style = MaterialTheme.typography.bodySmall,
                color = HushTextTertiary,
            )

            // --- Advanced, collapsed -------------------------------------
            // Self-hosting stays available; being told about it does not.
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableNoRipple { showAdvanced = !showAdvanced }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Advanced",
                    style = MaterialTheme.typography.labelLarge,
                    color = HushTextSecondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (showAdvanced) "−" else "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = HushTextTertiary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Use your own server",
                    style = MaterialTheme.typography.bodySmall,
                    color = HushTextTertiary,
                )
            }

            AnimatedVisibility(visible = showAdvanced, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = relay,
                        onValueChange = { relay = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "ws://127.0.0.1:8787/ws",
                                color = HushTextTertiary,
                                fontSize = 13.sp,
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HushAccent,
                            unfocusedBorderColor = HushSurfaceElevated,
                            focusedContainerColor = HushSurfaceElevated.copy(alpha = 0.4f),
                            unfocusedContainerColor = HushSurfaceElevated.copy(alpha = 0.25f),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "The server your messages pass through. It can read none of them. Everyone on this relay can see each other.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HushTextTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Self-hosting is the whole point of an open-source relay,
                    // so the local case is spelled out rather than left as a
                    // thing you have to infer from the protocol docs.
                    Text(
                        text = "Running your own? Use ws://127.0.0.1:8787/ws on this machine, " +
                            "or wss:// for a server elsewhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HushTextTertiary,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = submit,
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HushAccent,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = HushSurfaceElevated,
                    disabledContentColor = HushTextTertiary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_continue),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // Three concrete facts, not three adjectives. "Private" on a
            // landing page means nothing; "the relay keeps no history" does.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(HushSurfaceElevated.copy(alpha = 0.35f))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FactRow(Icons.Filled.Lock, "End-to-end encrypted", "The relay routes ciphertext it has no key to read.")
                FactRow(Icons.Filled.Shield, "No accounts", "No email, no phone number, no password, no recovery.")
                FactRow(Icons.Filled.Shield, "No history", "Messages are never stored on a server, anywhere.")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FactRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HushOnline,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = HushTextSecondary,
            )
        }
    }
}

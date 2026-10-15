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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hush.chat.ui.components.InitialsAvatar
import dev.hush.chat.ui.components.PulsingDot
import dev.hush.chat.ui.theme.HushAccent
import dev.hush.chat.ui.theme.HushOnline
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary
import dev.hush.chat.ui.theme.HushWarning

/**
 * Safety-number verification.
 *
 * This screen is the entire defence against a malicious relay, and it only
 * works if it is inconvenient to skip. So: the number is shown in full, the
 * compare button is the only action, and the copy explicitly says the relay
 * operator is the threat model.
 *
 * The copy is blunt on purpose. "Verify this number" tells a user nothing about
 * who to verify it *with*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyNumberScreen(
    peerName: String,
    peerSeed: Int,
    safetyNumber: String,
    verified: Boolean,
    onVerify: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val digits = safetyNumber.split(" ").filter { it.isNotEmpty() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Verify", style = MaterialTheme.typography.titleMedium) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            InitialsAvatar(
                initials = peerName.take(1).uppercase(),
                seed = peerSeed,
                size = 72.dp,
                verified = verified,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!verified) {
                    PulsingDot(color = HushWarning, animate = true, size = 6.dp)
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    text = if (verified) "Verified" else "Not verified",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (verified) HushOnline else HushWarning,
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Read these numbers to $peerName over a call you both recognise. If they match, no one is intercepting this conversation.",
                style = MaterialTheme.typography.bodyMedium,
                color = HushTextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // 60 digits in twelve rows of five. Laid out as a grid of grouped
            // blocks rather than one wrapped string, because a number you have
            // to re-read to find a position is a number people will not check.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(HushSurfaceElevated)
                    .padding(vertical = 20.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                digits.chunked(5).forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        row.forEachIndexed { columnIndex, block ->
                            Text(
                                text = block,
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.width(56.dp),
                                textAlign = TextAlign.Center,
                            )
                            if (columnIndex != row.lastIndex) {
                                // 1-of-12 shade so blocks are separable at a glance
                                Text(
                                    text = "",
                                    modifier = Modifier.width(0.dp),
                                )
                            }
                        }
                    }
                    if (rowIndex == 5) {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(digits.joinToString("")))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = HushTextSecondary)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "You can also copy this",
                    style = MaterialTheme.typography.bodySmall,
                    color = HushTextTertiary,
                )
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = { if (!verified) onVerify() },
                enabled = !verified,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HushAccent,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = HushSurfaceElevated,
                    disabledContentColor = HushOnline,
                ),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (verified) "Verified" else "Mark as verified",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "HUSH encrypts messages end-to-end, but the relay still routes them. Verification is how you detect a relay that is not honest. It cannot be done later without someone on the other end cooperating.",
                style = MaterialTheme.typography.bodySmall,
                color = HushTextTertiary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

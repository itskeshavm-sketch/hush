package dev.hush.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.hush.chat.UnlockResult
import dev.hush.chat.ui.theme.HushDanger
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The app gate.
 *
 * Deliberately indistinguishable whether or not a duress PIN exists: the same
 * pad, the same copy, no hint. A duress PIN that announces itself is not a
 * duress PIN. Entering it wipes the install (identity, chats, prefs, pins)
 * and lands on onboarding — there is nothing to find afterwards.
 */
@Composable
fun LockScreen(
    onUnlock: (String) -> UnlockResult,
    modifier: Modifier = Modifier,
) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldownUntil by remember { mutableStateOf(0L) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    suspend fun reject(message: String) {
        error = message
        entered = ""
        // A short lateral shake. Motion here answers the failure — it shows
        // the attempt was refused — rather than decorating the screen.
        shake.animateTo(
            1f,
            animationSpec = keyframes {
                durationMillis = 320
                0f at 0; -14f at 60; 12f at 140; -8f at 200; 5f at 260; 0f at 320
            },
        )
        shake.snapTo(0f)
    }

    fun submit() {
        if (entered.length < 4 || System.currentTimeMillis() < cooldownUntil) return
        when (val result = onUnlock(entered)) {
            UnlockResult.Open -> Unit // overlay lifts; nothing to show
            UnlockResult.Wiped -> Unit // same: onboarding is underneath
            is UnlockResult.Refused -> {
                if (result.retryInMs > 0) {
                    cooldownUntil = System.currentTimeMillis() + result.retryInMs
                    scope.launch { reject("Too many tries. Wait ${result.retryInMs / 1000}s.") }
                } else {
                    scope.launch { reject("Wrong PIN.") }
                }
            }
        }
    }

    fun press(digit: Char) {
        if (System.currentTimeMillis() < cooldownUntil) return
        error = null
        if (entered.length >= 12) return
        entered += digit
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            text = "HUSH",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Enter PIN",
            style = MaterialTheme.typography.bodyMedium,
            color = HushTextSecondary,
        )
        Spacer(Modifier.height(28.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
        ) {
            val n = entered.length.coerceAtLeast(4)
            repeat(n) { i ->
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < entered.length) MaterialTheme.colorScheme.onBackground
                            else HushSurfaceElevated,
                        ),
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = HushDanger)
        }

        Spacer(Modifier.weight(1f))

        // One explicit submit key. Probing the PIN on every keystroke would
        // burn failed attempts on prefixes and lock the owner out mid-entry.
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        keys.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .clickable {
                                when (key) {
                                    "⌫" -> {
                                        entered = entered.dropLast(1)
                                        error = null
                                    }
                                    "OK" -> submit()
                                    else -> press(key.single())
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (key == "OK") HushTextSecondary else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "This PIN is separate from the phone lock.",
            style = MaterialTheme.typography.bodySmall,
            color = HushTextTertiary,
        )
        Spacer(Modifier.height(24.dp))
    }
}

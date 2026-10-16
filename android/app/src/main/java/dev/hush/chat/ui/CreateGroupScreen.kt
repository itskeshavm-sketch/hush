package dev.hush.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import dev.hush.chat.data.Peer
import dev.hush.chat.data.SessionManager
import dev.hush.chat.ui.components.InitialsAvatar
import dev.hush.chat.ui.theme.HushAccent
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary

/**
 * New private group: name it, search people, tick members.
 *
 * Private means exactly what it says: the group exists only in its members'
 * databases. The relay routes the pairwise envelopes like any other message
 * and never learns there is a group at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    peers: List<Peer>,
    onBack: () -> Unit,
    onCreate: (name: String, memberIds: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val maxOthers = SessionManager.MAX_GROUP_MEMBERS - 1

    val shown = remember(peers, query) {
        val sorted = peers.sortedBy { it.displayName.lowercase() }
        if (query.isBlank()) sorted
        else sorted.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("New private group", style = MaterialTheme.typography.titleMedium) },
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
                .padding(horizontal = 20.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(SessionManager.MAX_GROUP_NAME_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Group name", color = HushTextTertiary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HushAccent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = HushSurfaceElevated,
                    unfocusedContainerColor = HushSurfaceElevated,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Only members ever see this group — it is never announced anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = HushTextTertiary,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search people to add", color = HushTextTertiary) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = HushTextTertiary)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HushAccent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = HushSurfaceElevated,
                    unfocusedContainerColor = HushSurfaceElevated,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${selected.size} of up to $maxOthers selected",
                style = MaterialTheme.typography.bodySmall,
                color = HushTextSecondary,
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(shown, key = { it.id }) { peer ->
                    val checked = peer.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selected = if (checked) selected - peer.id
                                else if (selected.size < maxOthers) selected + peer.id
                                else selected
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InitialsAvatar(
                            initials = peer.initials,
                            seed = peer.displayName.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF },
                            size = 40.dp,
                            online = null,
                            verified = peer.verified,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = peer.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        androidx.compose.material3.Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "no-match") {
                        BoxNoMatch()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onCreate(name, selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = HushAccent),
            ) {
                Text("Create group")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BoxNoMatch() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Nobody called that is here",
            style = MaterialTheme.typography.bodyMedium,
            color = HushTextSecondary,
        )
    }
}

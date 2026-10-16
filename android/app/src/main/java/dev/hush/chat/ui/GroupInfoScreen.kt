package dev.hush.chat.ui

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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
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
import dev.hush.chat.data.GroupInfo
import dev.hush.chat.data.Peer
import dev.hush.chat.data.SessionManager
import dev.hush.chat.ui.components.InitialsAvatar
import dev.hush.chat.ui.components.SectionHeader
import dev.hush.chat.ui.theme.HushAccent
import dev.hush.chat.ui.theme.HushDanger
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary
import dev.hush.chat.ui.theme.HushWarning

/**
 * One group's details: members, admin controls, danger zone.
 *
 * Admin-only rows (add, remove, rename) render only for the admin — everyone
 * else gets a read-only member list and Leave. Names here are labels; the
 * safety numbers still live on the underlying 1:1 sessions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    group: GroupInfo,
    peers: List<Peer>,
    selfId: String?,
    onBack: () -> Unit,
    onAddMembers: (List<String>) -> Unit,
    onRemoveMember: (String) -> Unit,
    onRename: (String) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAdmin = selfId != null && group.adminId == selfId
    val byId = remember(peers) { peers.associateBy { it.id } }
    var addQuery by remember { mutableStateOf("") }
    var renameOpen by remember { mutableStateOf(false) }
    var renameDraft by remember(group.name) { mutableStateOf(group.name) }

    fun nameOf(id: String): String {
        if (id == selfId) return "You"
        return byId[id]?.displayName ?: "Unknown"
    }

    val candidates = remember(peers, group, addQuery) {
        peers.filter { it.id !in group.memberIds }
            .let { if (addQuery.isBlank()) it else it.filter { p -> p.displayName.contains(addQuery.trim(), ignoreCase = true) } }
            .sortedBy { it.displayName.lowercase() }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(group.name, style = MaterialTheme.typography.titleMedium) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item(key = "meta") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = "${group.memberIds.size} members · private to members",
                        style = MaterialTheme.typography.bodySmall,
                        color = HushTextSecondary,
                    )
                    if (isAdmin) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "You are the admin",
                            style = MaterialTheme.typography.bodySmall,
                            color = HushWarning,
                        )
                    }
                }
            }

            item(key = "members-header") { SectionHeader("Members") }
            items(group.memberIds, key = { "m:$it" }) { id ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InitialsAvatar(
                        initials = nameOf(id).take(1).uppercase(),
                        seed = id.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF },
                        size = 40.dp,
                        online = null,
                        verified = false,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = nameOf(id),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                            )
                            if (id == group.adminId) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "admin",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = HushWarning,
                                )
                            }
                        }
                    }
                    if (isAdmin && id != selfId) {
                        IconButton(onClick = { onRemoveMember(id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove ${nameOf(id)}",
                                tint = HushDanger,
                            )
                        }
                    }
                }
            }

            if (isAdmin) {
                item(key = "add-header") { SectionHeader("Add members") }
                item(key = "add-search") {
                    OutlinedTextField(
                        value = addQuery,
                        onValueChange = { addQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        placeholder = { Text("Search people", color = HushTextTertiary) },
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
                }
                items(candidates, key = { "c:${it.id}" }) { peer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = { onAddMembers(listOf(peer.id)) })
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = peer.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add ${peer.displayName}",
                            tint = HushAccent,
                        )
                    }
                }
                item(key = "rename") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = {
                                renameDraft = group.name
                                renameOpen = true
                            })
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = HushTextSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Rename group",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            item(key = "leave") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onLeave)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = HushDanger)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Leave and delete group",
                        style = MaterialTheme.typography.titleMedium,
                        color = HushDanger,
                    )
                }
            }
        }
    }

    if (renameOpen) {
        RenameDialog(
            initial = renameDraft,
            onConfirm = {
                renameOpen = false
                onRename(it)
            },
            onDismiss = { renameOpen = false },
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename group") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(SessionManager.MAX_GROUP_NAME_CHARS) },
                singleLine = true,
                placeholder = { Text("Group name", color = HushTextTertiary) },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (draft.isNotBlank()) onConfirm(draft) },
            ) { Text("Rename") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

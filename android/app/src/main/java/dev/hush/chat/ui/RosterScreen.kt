package dev.hush.chat.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hush.chat.data.GroupInfo
import dev.hush.chat.data.Peer
import dev.hush.chat.net.RelayState
import dev.hush.chat.ui.components.ConnectionBanner
import dev.hush.chat.ui.components.EmptyState
import dev.hush.chat.ui.components.InitialsAvatar
import dev.hush.chat.ui.components.SectionHeader
import dev.hush.chat.ui.theme.HushAccent
import dev.hush.chat.ui.theme.HushDanger
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary
import dev.hush.chat.ui.theme.HushWarning
import java.util.Locale

/**
 * "All Users" — every peer currently on the relay.
 *
 * This is the discovery surface HUSH is built around: there is no invite link,
 * no directory, no search-by-handle. You install it, you connect, you appear.
 * Anyone else who does the same sees you. That is a deliberate trade — it
 * means an open relay is a public room, and the copy in Settings says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterScreen(
    peers: List<Peer>,
    groups: List<GroupInfo> = emptyList(),
    displayName: String,
    relayState: RelayState,
    onOpenPeer: (Peer) -> Unit,
    onOpenGroup: (GroupInfo) -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryRelay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // No online/offline sections: presence was never accurate enough to show
    // (stale "online" for gone peers), so the list no longer claims it. The
    // app still tracks reachability internally to flush the offline outbox —
    // it just stopped printing guesses. Alphabetical, stable.
    var query by remember { mutableStateOf("") }
    val sorted = peers.sortedBy { it.displayName.lowercase() }
    val shownPeers = remember(sorted, query) {
        if (query.isBlank()) sorted
        else sorted.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
    }
    val shownGroups = remember(groups, query) {
        if (query.isBlank()) groups
        else groups.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val totalUnread = peers.sumOf { it.unreadCount }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "HUSH Modern",
                            style = MaterialTheme.typography.titleLarge,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp),
                        )
                        Text(
                            text = if (displayName.isBlank()) "All users" else displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = HushTextTertiary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search messages",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(1.dp).background(HushSurfaceElevated),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
        ) {
            ConnectionBanner(state = relayState, onRetry = onRetryRelay)

            // Username search: the relay is one public room, so finding a
            // person is filtering the list, not a server query.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text("Search people and groups", color = HushTextTertiary) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = HushTextTertiary)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear search",
                                tint = HushTextTertiary,
                            )
                        }
                    }
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

            if (peers.isEmpty() && groups.isEmpty()) {
                // Nothing to configure, nothing to share, nothing to type.
                //
                // Every install points at the same public relay by default, so
                // "share the relay address with them" was instructing the user
                // to do something that is both unnecessary and impossible —
                // there is no address to hand out. Two people who install the
                // app simply appear to each other.
                EmptyState(
                    title = "Nobody else is here yet",
                    body = "Anyone else who opens HUSH shows up here on its own. " +
                        "No accounts, no codes, no setup.",
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                if (shownGroups.isNotEmpty()) {
                    item(key = "groups-header") {
                        SectionHeader("Groups")
                    }
                    items(shownGroups, key = { "g:" + it.id }) { group ->
                        GroupRow(group, onClick = { onOpenGroup(group) })
                    }
                    item(key = "new-group") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onCreateGroup)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.PersonAdd,
                                contentDescription = null,
                                tint = HushAccent,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = "New private group",
                                style = MaterialTheme.typography.titleMedium,
                                color = HushAccent,
                            )
                        }
                    }
                } else if (query.isBlank()) {
                    item(key = "new-group") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onCreateGroup)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.PersonAdd,
                                contentDescription = null,
                                tint = HushAccent,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = "New private group",
                                style = MaterialTheme.typography.titleMedium,
                                color = HushAccent,
                            )
                        }
                    }
                }
                if (shownPeers.isNotEmpty()) {
                    if (shownGroups.isNotEmpty() || query.isNotBlank()) {
                        item(key = "people-header") {
                            SectionHeader("People")
                        }
                    }
                    items(shownPeers, key = { it.id }) { peer ->
                        PeerRow(peer, onClick = { onOpenPeer(peer) })
                    }
                }
                if (shownPeers.isEmpty() && shownGroups.isEmpty() && query.isNotBlank()) {
                    item(key = "no-match") {
                        Box(
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
                }
            }        }
    }
}

@Composable
private fun PeerRow(peer: Peer, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(
            initials = peer.initials,
            seed = peer.avatarSeed(),
            size = 46.dp,
            online = null,
            verified = peer.verified,
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                if (!peer.verified && peer.safetyNumber.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "unverified",
                        style = MaterialTheme.typography.labelSmall,
                        color = HushWarning,
                    )
                }
            }
            Text(
                text = when {
                    peer.unreadCount > 0 -> "${peer.unreadCount} unread"
                    // Unread is "arrived since you last opened this chat", so
                    // zero means you have read everything that arrived.
                    else -> "read"
                },
                style = MaterialTheme.typography.bodySmall,
                color = HushTextSecondary,
            )
        }

        if (peer.unreadCount > 0) {
            Badge(containerColor = HushAccent) {
                Text("${peer.unreadCount}", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(
            initials = group.initials,
            seed = group.id.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF },
            size = 46.dp,
            online = null,
            verified = false,
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    append("${group.memberIds.size} members")
                    append(if (group.unreadCount > 0) " · ${group.unreadCount} unread" else " · read")
                },
                style = MaterialTheme.typography.bodySmall,
                color = HushTextSecondary,
            )
        }

        if (group.unreadCount > 0) {
            Badge(containerColor = HushAccent) {
                Text("${group.unreadCount}", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

/**
 * Stable per-peer avatar seed.
 *
 * Derived from the peer id rather than the display name: names change, ids do
 * not, and an avatar that changes colour because someone fixed a typo is
 * disorienting.
 */
private fun Peer.avatarSeed(): Int = id.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }

/** "just now" / "14m" / "3h" / "12 Mar". */
fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0L) return "never"
    val delta = System.currentTimeMillis() - epochMs
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        // DateTimeFormatter rather than a shared SimpleDateFormat: the latter
        // is mutable and not thread-safe, and this runs under recomposition.
        else -> java.time.format.DateTimeFormatter
            .ofPattern("d MMM", Locale.getDefault())
            .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))
    }
}

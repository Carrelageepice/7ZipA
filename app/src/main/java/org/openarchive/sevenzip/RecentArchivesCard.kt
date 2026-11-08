package org.openarchive.sevenzip

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The last few archives you opened, one tap to open again. Draws nothing when
 * the list is empty. [refreshKey] should change whenever you call
 * RecentArchives.add so the list redraws (for example the selected archive Uri).
 */
@Composable
fun RecentArchivesCard(
    refreshKey: Any?,
    modifier: Modifier = Modifier,
    onOpen: (Uri, String) -> Unit
) {
    val context: Context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val items = remember(refreshKey, version) { RecentArchives.load(context) }
    if (items.isEmpty()) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Recent archives", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { RecentArchives.clear(context); version++ }) {
                    Text("Clear")
                }
            }
            for (item in items) {
                TextButton(
                    onClick = {
                        if (RecentArchives.isReadable(context, item.uri)) {
                            onOpen(item.uri, item.name)
                        } else {
                            // Moved, deleted, or access was not kept.
                            RecentArchives.remove(context, item.uri)
                            version++
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

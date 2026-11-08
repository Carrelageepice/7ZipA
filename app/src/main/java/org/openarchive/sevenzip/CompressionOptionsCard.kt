package org.openarchive.sevenzip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Output formats the engine can create. */
val CREATE_FORMATS = listOf("7z", "zip", "tar", "tar.gz", "tar.xz", "tar.bz2")

/**
 * Advanced settings for the Create screen: algorithm, solid mode, dictionary
 * size, CPU threads. Shows only what applies to [format]; if there is nothing
 * to set (plain tar, tar.gz...) it shows nothing at all.
 *
 *   var options by remember { mutableStateOf(CompressionOptions()) }
 *   CompressionOptionsCard(format, options) { options = it }
 *   // later: ArchiveOps.createToTree(..., progress, options)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompressionOptionsCard(
    format: String,
    options: CompressionOptions,
    modifier: Modifier = Modifier,
    onChange: (CompressionOptions) -> Unit
) {
    val is7z = format == "7z"
    val isZip = format == "zip"
    if (!is7z && !isZip) return

    val methods = if (is7z) listOf("lzma2", "lzma", "ppmd", "bzip2", "deflate", "copy")
    else listOf("deflate", "deflate64", "bzip2", "lzma", "ppmd", "copy")
    val usesDictionary = is7z && (options.method.isEmpty() || options.method == "lzma2" || options.method == "lzma")

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Algorithm", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = options.method.isEmpty(),
                onClick = { onChange(options.copy(method = "")) },
                label = { Text("Auto") }
            )
            for (m in methods) {
                FilterChip(
                    selected = options.method == m,
                    onClick = { onChange(options.copy(method = m)) },
                    label = { Text(m.uppercase()) }
                )
            }
        }
        if (isZip && options.method.isNotEmpty() && options.method !in listOf("deflate", "copy")) {
            Text(
                "Other ZIP tools may not open ZIP files that use this algorithm. " +
                    "Deflate works everywhere.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (is7z) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Solid archive", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Smaller files, but extracting one file is slower.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = options.solid != 0,
                    onCheckedChange = { onChange(options.copy(solid = if (it) -1 else 0)) }
                )
            }
        }

        if (usesDictionary) {
            Text("Dictionary size (bigger = smaller file, more memory)", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mb in listOf(0, 1, 4, 16, 64)) {
                    FilterChip(
                        selected = options.dictMb == mb,
                        onClick = { onChange(options.copy(dictMb = mb)) },
                        label = { Text(if (mb == 0) "Auto" else "$mb MB") }
                    )
                }
            }
        }

        Text("CPU threads", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in listOf(0, 1, 2, 4)) {
                FilterChip(
                    selected = options.threads == t,
                    onClick = { onChange(options.copy(threads = t)) },
                    label = { Text(if (t == 0) "Auto" else "$t") }
                )
            }
        }
    }
}

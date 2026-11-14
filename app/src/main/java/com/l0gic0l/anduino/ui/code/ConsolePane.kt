package com.l0gic0l.anduino.ui.code

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.l0gic0l.anduino.core.ConsoleEntry
import com.l0gic0l.anduino.core.ConsoleKind
import com.l0gic0l.anduino.ui.theme.MonoFamily

/** Compile / upload output, auto-scrolling while the user is at the bottom. */
@Composable
fun ConsolePane(entries: List<ConsoleEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(entries.size, entries.lastOrNull()?.id) {
        if (entries.isNotEmpty() && (atBottom || listState.layoutInfo.totalItemsCount <= 1)) {
            listState.scrollToItem(entries.size - 1)
        }
    }
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Text(
                "Output of Verify and Upload appears here.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(entries, key = { it.id }) { e ->
                    Text(
                        e.text,
                        fontFamily = MonoFamily,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = when (e.kind) {
                            ConsoleKind.ERROR -> colors.error
                            ConsoleKind.COMMAND -> colors.onSurfaceVariant
                            ConsoleKind.INFO -> colors.tertiary
                            ConsoleKind.SUCCESS -> colors.primary
                            ConsoleKind.OUTPUT -> colors.onSurface
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

package com.l0gic0l.anduino.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.l0gic0l.anduino.core.JobState

/** Thin status strip shown while a background job (compile, install...) runs. */
@Composable
fun JobBar(state: JobState?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    if (state == null) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    // A ticking clock shows the job is alive even when a step takes minutes.
                    val elapsed by produceState(0L, state.startedAt) {
                        while (true) {
                            value = (System.currentTimeMillis() - state.startedAt) / 1000
                            delay(1000)
                        }
                    }
                    Text(
                        "${state.title} · ${"%d:%02d".format(elapsed / 60, elapsed % 60)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    state.step?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
            }
            if (state.progress != null) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
    }
}

/**
 * A draggable divider between two panes. [onDrag] receives the pointer delta in px
 * along the split axis.
 */
@Composable
fun SplitHandle(vertical: Boolean, onDrag: (Float) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberDraggableState(onDrag)
    val touch: Dp = 16.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(if (vertical) Modifier.fillMaxHeight().width(touch) else Modifier.fillMaxWidth().height(touch))
            .draggable(state, if (vertical) Orientation.Horizontal else Orientation.Vertical)
            .semantics { contentDescription = "Resize panes" },
    ) {
        Box(
            Modifier
                .then(if (vertical) Modifier.width(4.dp).height(40.dp) else Modifier.height(4.dp).width(40.dp))
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

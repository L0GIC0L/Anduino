package com.l0gic0l.anduino.ui.serial

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Full-screen Serial Monitor + Plotter. Wide windows show both at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerialScreen(vm: SerialViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Serial") })
        SerialToolbar(vm)
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth >= 840.dp || (maxWidth >= 600.dp && maxWidth > maxHeight)) {
                Row(Modifier.fillMaxSize()) {
                    MonitorPanel(vm, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        PlotterPanel(vm, Modifier.fillMaxSize())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Monitor") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Plotter") })
                    }
                    if (tab == 0) MonitorPanel(vm, Modifier.weight(1f)) else PlotterPanel(vm, Modifier.weight(1f))
                }
            }
        }
    }
}

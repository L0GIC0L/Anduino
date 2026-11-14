package com.l0gic0l.anduino.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.l0gic0l.anduino.AppContainer
import com.l0gic0l.anduino.ui.code.CodeScreen
import com.l0gic0l.anduino.ui.code.IdeViewModel
import com.l0gic0l.anduino.ui.manager.ManagerScreen
import com.l0gic0l.anduino.ui.manager.ManagerViewModel
import com.l0gic0l.anduino.ui.serial.SerialScreen
import com.l0gic0l.anduino.ui.serial.SerialViewModel
import com.l0gic0l.anduino.ui.settings.SettingsScreen
import com.l0gic0l.anduino.ui.setup.SetupScreen
import com.l0gic0l.anduino.ui.sketchbook.SketchbookScreen
import kotlinx.coroutines.flow.merge

enum class Destination(val label: String, val icon: ImageVector) {
    CODE("Code", Icons.Default.Code),
    SKETCHBOOK("Sketches", Icons.Default.FolderOpen),
    SERIAL("Serial", Icons.Default.Terminal),
    MANAGER("Boards & Libs", Icons.Default.DeveloperBoard),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun AppRoot(c: AppContainer) {
    val ready by c.envReady.collectAsStateWithLifecycle()
    if (!ready) SetupScreen(c) else MainScaffold(c)
}

@Composable
private fun MainScaffold(c: AppContainer) {
    var dest by rememberSaveable { mutableStateOf(Destination.CODE) }
    var managerTab by rememberSaveable { mutableIntStateOf(0) }
    val ide: IdeViewModel = viewModel { IdeViewModel(c) }
    val serial: SerialViewModel = viewModel { SerialViewModel(c) }
    val manager: ManagerViewModel = viewModel { ManagerViewModel(c) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        merge(ide.messages, serial.messages, manager.messages).collect { snackbar.showSnackbar(it) }
    }

    // While typing (code, serial input, search), give the screen everything above the keyboard.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val adaptiveType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())
    val layoutType = if (imeVisible) NavigationSuiteType.None else adaptiveType
    val contentInsets = when (layoutType) {
        NavigationSuiteType.NavigationBar -> Modifier
        NavigationSuiteType.None -> Modifier.imePadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        else -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            Destination.entries.forEach { d ->
                item(
                    selected = dest == d,
                    onClick = { dest = d },
                    icon = { Icon(d.icon, contentDescription = null) },
                    label = { Text(d.label) },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().then(contentInsets)) {
            when (dest) {
                Destination.CODE -> CodeScreen(
                    vm = ide,
                    serialVm = serial,
                    prefs = c.prefs,
                    onOpenSketchbook = { dest = Destination.SKETCHBOOK },
                    onOpenBoardsManager = { managerTab = 0; dest = Destination.MANAGER },
                )
                Destination.SKETCHBOOK -> SketchbookScreen(ide, onOpened = { dest = Destination.CODE })
                Destination.SERIAL -> SerialScreen(serial)
                Destination.MANAGER -> ManagerScreen(manager, managerTab)
                Destination.SETTINGS -> SettingsScreen(c)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

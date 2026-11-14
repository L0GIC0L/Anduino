package com.l0gic0l.anduino

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.l0gic0l.anduino.core.FastBuild
import com.l0gic0l.anduino.env.ExecServer
import com.l0gic0l.anduino.env.NativeExec
import java.io.File
import com.l0gic0l.anduino.cli.ArduinoCli
import com.l0gic0l.anduino.core.BuildActions
import com.l0gic0l.anduino.core.ConsoleLog
import com.l0gic0l.anduino.core.JobManager
import com.l0gic0l.anduino.core.Prefs
import com.l0gic0l.anduino.core.ToolchainRepair
import com.l0gic0l.anduino.env.Bootstrapper
import com.l0gic0l.anduino.env.LinuxEnv
import com.l0gic0l.anduino.serial.SerialMonitor
import com.l0gic0l.anduino.service.WorkService
import com.l0gic0l.anduino.sketch.SketchRepository
import com.l0gic0l.anduino.usb.UsbPorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Hand-written dependency container; the app is small enough not to need a DI framework. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val prefs = Prefs(context)
    val env = LinuxEnv(context).apply { disableSeccomp = prefs.disableSeccomp.value }
    val cli = ArduinoCli(env)
    val console = ConsoleLog(appScope)
    val jobs = JobManager(context, appScope, console)
    val usbPorts = UsbPorts(context)
    val monitor = SerialMonitor(usbPorts, appScope)
    val sketches = SketchRepository(env.hostPath(LinuxEnv.SKETCHBOOK))
    val bootstrapper = Bootstrapper(env, context.cacheDir)
    val native = NativeExec(
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        rootfs = env.rootfs,
        glibcDir = File(env.base, "glibc"),
        triplet = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64-linux-gnu" else "aarch64-linux-gnu",
    )
    val execServer = ExecServer(native, env.hostPath(NativeExec.CONF_GUEST_PATH)) { console.info(it) }
    val fastBuild = FastBuild(env, native, execServer, console)
    val build = BuildActions(env, cli, usbPorts, monitor, console, prefs, fastBuild)
    val repair = ToolchainRepair(env, cli, console)

    /** False until the Linux environment and arduino-cli are installed. */
    val envReady = MutableStateFlow(env.isInstalled)

    /** DNS servers of the active network, for the rootfs' resolv.conf. */
    fun dnsServers(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        return lp.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }
    }
}

class AnduinoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WorkService.ensureChannel(this)
    }
}

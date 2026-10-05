package my.torrstream.server

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Сервер TorrStream на телефоне: TorrServer + сервер TorrStream (Node) + ffmpeg, к
 * которому подключаются телевизоры (Vidaa и др.) по адресу http://<IP телефона>:3000.
 *
 * Foreground-сервис с уведомлением, плюс WakeLock и WifiLock: с погасшим экраном
 * телефон иначе усыпляет процессор и Wi-Fi, и телевизор теряет сервер посреди фильма.
 * Процессы — дочерние; упавший перезапускается, но не чаще [MAX_RESTARTS] раз за
 * [RESTART_WINDOW_MS].
 */
class ServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            // TorrServer установили или включили «запускать вместе с сервером», пока сервер работает
            ACTION_TS_START -> if (running && tsProc == null && !tsExternal) {
                Thread({ startTorrServerIfWanted() }, "ts-start").start()
            }
            ACTION_TS_STOP -> stopTorrServer()
            else -> if (!running) {
                running = true
                acquireLocks()
                Thread({ startAll() }, "server-start").start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        nodeProc?.let { p -> try { p.destroy() } catch (_: Exception) { } }
        tsProc?.let { p -> try { p.destroy() } catch (_: Exception) { } }
        nodeProc = null
        tsProc = null
        serverState = "Остановлен"
        // Чужой TorrServer (TorrServe) мы не запускали — он и дальше работает
        if (!tsExternal) torrServerState = "Остановлен"
        releaseLocks()
        log("■ Остановлено")
        super.onDestroy()
    }

    // ==================== ЗАПУСК ====================

    private fun startAll() {
        val ctx = applicationContext
        // 1. TorrServer — по желанию (установка и флажок на экране приложения)
        startTorrServerIfWanted()
        // 2. Сервер TorrStream
        if (running) startNode()
    }

    /**
     * TorrServer на телефоне — по желанию. Уже работает чужой на порту 8090 (TorrServe) —
     * пользуемся им. Свой — только если установлен и включён «запускать вместе с
     * сервером». Иначе телевизор работает с TorrServer, указанным в его настройках.
     */
    private fun startTorrServerIfWanted() {
        val ctx = applicationContext
        val external = TorrServerInstaller.runningVersion()
        tsExternal = external != null
        when {
            external != null -> {
                torrServerState = "Работает (уже был запущен): $external"
                log("TorrServer уже работает на порту ${Env.TORRSERVER_PORT} ($external) — свою копию не запускаем")
            }
            !TorrServerInstaller.isInstalled(ctx) -> torrServerState = "Не установлен"
            !TorrServerInstaller.enabled(ctx) -> torrServerState = "Установлен, не запускается (выключено)"
            running -> startTorrServer()
        }
    }

    private fun stopTorrServer() {
        val p = tsProc ?: return
        tsProc = null      // onExit не станет перезапускать
        try { p.destroy() } catch (_: Exception) { }
        torrServerState = "Остановлен"
        log("TorrServer остановлен")
    }

    private fun startTorrServer() {
        val ctx = applicationContext
        val bin = TorrServerInstaller.binary(ctx)
        val data = TorrServerInstaller.dataDir(ctx)
        val p = try {
            ProcessBuilder(bin.absolutePath, "-p", Env.TORRSERVER_PORT.toString(), "-d", data.absolutePath)
                .directory(data).redirectErrorStream(true).start()
        } catch (e: Exception) {
            torrServerState = "Не запустился: ${e.message}"
            log("TorrServer не запустился: ${e.message}")
            return
        }
        tsProc = p
        torrServerState = "Работает: ${TorrServerInstaller.version(ctx) ?: ""}"
        log("TorrServer запущен")
        pump(p, "[TS] ") { code -> onExit(p, code, "TorrServer", tsRestarts) { startTorrServer() } }
    }

    private fun startNode() {
        val ctx = applicationContext
        try {
            prepareNodeLibs(ctx)
            prepareServerApp(ctx)
        } catch (e: Exception) {
            serverState = "Не удалось подготовить сервер: ${e.message}"
            log("Подготовка сервера: ${e.message}")
            return
        }
        // Отладка: files/server.bin, если есть, запускается вместо node
        val debugBin = File(ctx.filesDir, "server.bin").takeIf { it.canExecute() }
        val bin = debugBin ?: Env.node(ctx)
        if (!bin.exists()) {
            serverState = "Нет node в APK"
            log("Нет ${bin.path}")
            return
        }
        val home = Env.serverHome(ctx)
        // Отладка без пересборки APK: дополнительные аргументы Node (по одному в строке) и
        // переменные окружения (KEY=VALUE) из files/server.args и files/server.env
        val extraArgs = File(ctx.filesDir, "server.args").takeIf { it.isFile }?.readLines()
            ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
        val extraEnv = File(ctx.filesDir, "server.env").takeIf { it.isFile }?.readLines()
            ?.map { it.trim() }?.filter { it.contains('=') && !it.startsWith("#") }.orEmpty()
        val script = File(Env.serverApp(ctx), "server.js").absolutePath
        val cmd = if (debugBin != null) listOf(bin.absolutePath) + extraArgs
        else listOf(bin.absolutePath) + extraArgs + script
        val pb = ProcessBuilder(cmd).directory(home).redirectErrorStream(true)
        pb.environment().apply {
            put("PORT", Env.SERVER_PORT.toString())
            put("TORRSTREAM_HOME", home.absolutePath)
            put("HLS_DIR", Env.hlsDir(ctx).absolutePath)
            put("FFMPEG_PATH", Env.ffmpeg(ctx).absolutePath)
            put("FFPROBE_PATH", Env.ffprobe(ctx).absolutePath)
            // Библиотеки Node (libssl.so.3, libicuuc.so.78…) — по ссылкам из prepareNodeLibs
            put("LD_LIBRARY_PATH", Env.nodeLibDir(ctx).absolutePath)
            // Корневые сертификаты — системные Android: у OpenSSL из Termux свой путь,
            // которого на телефоне нет. DNS — системный (bionic), TORRSTREAM_DNS не нужен
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            put("HOME", home.absolutePath)
            put("TMPDIR", ctx.cacheDir.absolutePath)
            // io_uring приложениям Android запрещён фильтром системных вызовов (seccomp):
            // libuv, решив им воспользоваться, получил бы SIGSYS
            put("UV_USE_IO_URING", "0")
            extraEnv.forEach { put(it.substringBefore('='), it.substringAfter('=')) }
        }
        if (extraArgs.isNotEmpty() || extraEnv.isNotEmpty()) log("Отладка: аргументы $extraArgs, окружение $extraEnv")
        val p = try {
            pb.start()
        } catch (e: Exception) {
            serverState = "Не запустился: ${e.message}"
            log("Сервер не запустился: ${e.message}")
            return
        }
        nodeProc = p
        serverState = "Работает"
        log("Сервер TorrStream запущен")
        pump(p, "") { code -> onExit(p, code, "Сервер TorrStream", nodeRestarts) { startNode() } }
    }

    /**
     * Библиотеки Node лежат в nativeLibraryDir под закодированными именами
     * (tools/fetch-node.py): libdep_<hex имени>.so — сама библиотека,
     * libdeplink_<hex ссылки>_<hex цели>.so — пустая метка символической ссылки.
     * Здесь — ссылки с настоящими именами, по ним их находит загрузчик (LD_LIBRARY_PATH).
     */
    private fun prepareNodeLibs(ctx: Context) {
        val native = Env.nativeDir(ctx)
        val dir = Env.nodeLibDir(ctx)
        dir.deleteRecursively()
        dir.mkdirs()
        fun decode(hex: String) = String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        native.listFiles().orEmpty().forEach { f ->
            val n = f.name
            when {
                n.startsWith("libdeplink_") -> {
                    val (link, target) = n.removePrefix("libdeplink_").removeSuffix(".so").split('_')
                    val real = File(native, "libdep_$target.so")
                    if (real.exists()) Os.symlink(real.absolutePath, File(dir, decode(link)).absolutePath)
                }
                n.startsWith("libdep_") ->
                    Os.symlink(f.absolutePath, File(dir, decode(n.removePrefix("libdep_").removeSuffix(".so"))).absolutePath)
            }
        }
    }

    /**
     * Код сервера: скачанное обновление (ServerUpdater), если оно новее установленного
     * APK, иначе встроенный assets/server.zip. Распаковывается заново, только когда
     * источник сменился.
     */
    private fun prepareServerApp(ctx: Context) {
        val dir = Env.serverApp(ctx)
        val apkUpdated = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        ServerUpdater.dropIfOlderThanApk(ctx, apkUpdated)
        val update = ServerUpdater.downloaded(ctx).takeIf { it.isFile }
        val stamp = if (update != null) "update:${ServerUpdater.downloadedAt(ctx)}" else "apk:$apkUpdated"
        val marker = File(dir, ".installed")
        if (marker.isFile && marker.readText() == stamp) return
        dir.deleteRecursively()
        dir.mkdirs()
        val source = if (update != null) update.inputStream() else ctx.assets.open("server.zip")
        ZipInputStream(source).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dir, e.name)
                // Защита от «../» в именах архива
                if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        marker.writeText(stamp)
        log("Код сервера распакован (${if (update != null) "обновление" else "из APK"}): ${ServerUpdater.currentVersion(ctx) ?: "?"}")
    }

    /** Вывод процесса — в журнал (его обязательно вычитывать: полный буфер останавливает процесс) */
    private fun pump(p: Process, prefix: String, onDone: (Int) -> Unit) {
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { log(prefix + it) }
            } catch (_: Exception) { }
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            onDone(code)
        }, "proc-output").start()
    }

    private fun onExit(p: Process, code: Int, name: String, restarts: ArrayList<Long>, restart: () -> Unit) {
        if (!running) return
        if (p !== nodeProc && p !== tsProc) return
        log("$name завершился, код $code")
        if (p === nodeProc) { nodeProc = null; serverState = "Перезапуск…" }
        if (p === tsProc) { tsProc = null; torrServerState = "Перезапуск…" }
        val now = System.currentTimeMillis()
        synchronized(restarts) {
            restarts.removeAll { now - it > RESTART_WINDOW_MS }
            if (restarts.size >= MAX_RESTARTS) {
                if (name.startsWith("TorrServer")) torrServerState = "Падает — остановлен, см. журнал"
                else serverState = "Падает — остановлен, см. журнал"
                log("$name падает раз за разом — больше не перезапускаем")
                return
            }
            restarts += now
        }
        Thread.sleep(RESTART_DELAY_MS)
        if (running) restart()
    }

    // ==================== СОН ====================

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TorrStream:server").apply { acquire() }
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TorrStream:server").apply { acquire() }
        } catch (e: Exception) {
            log("Не удалось удержать процессор/Wi-Fi: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wakeLock = null
        wifiLock = null
    }

    // ==================== УВЕДОМЛЕНИЕ ====================

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val addr = Env.lanAddresses().firstOrNull()?.let { "http://$it:${Env.SERVER_PORT}" } ?: "нет сети"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, addr))
            .setSmallIcon(R.drawable.lampa_icon)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "TorrStreamServer"
        private const val CHANNEL_ID = "server"
        private const val NOTIF_ID = 3000
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 5 * 60 * 1000L
        private const val RESTART_DELAY_MS = 3000L
        private const val LOG_LINES = 400
        private const val LOG_FILE_MAX = 2L * 1024 * 1024

        const val ACTION_TS_START = "my.torrstream.server.TS_START"
        const val ACTION_TS_STOP = "my.torrstream.server.TS_STOP"

        @Volatile var running = false; private set
        @Volatile var serverState = "Остановлен"; private set
        @Volatile var torrServerState = "Остановлен"; private set
        @Volatile private var nodeProc: Process? = null
        @Volatile private var tsProc: Process? = null
        @Volatile private var tsExternal = false
        private val nodeRestarts = ArrayList<Long>()
        private val tsRestarts = ArrayList<Long>()

        private val lines = ArrayDeque<String>()
        private var logFile: File? = null

        fun init(ctx: Context) {
            if (logFile == null) logFile = Env.serverLog(ctx.applicationContext)
        }

        fun log(line: String) {
            Log.i(TAG, line)
            synchronized(lines) {
                lines.addLast(line)
                while (lines.size > LOG_LINES) lines.removeFirst()
                logFile?.let { f ->
                    try {
                        if (f.length() > LOG_FILE_MAX) f.delete()
                        FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()) }
                    } catch (_: Exception) { }
                }
            }
        }

        fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

        /** Очистка экрана журнала; файл server.log не трогаем — он для разбора проблем */
        fun clearLog() = synchronized(lines) { lines.clear() }

        /** Весь server.log (до 2 МБ) — чтобы отправить разработчику */
        fun logFileText(): String = try {
            logFile?.takeIf { it.isFile }?.readText() ?: logText()
        } catch (_: Exception) {
            logText()
        }

        fun start(ctx: Context) {
            init(ctx)
            val intent = Intent(ctx, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ServerService::class.java))
        }

        /** Перезапуск — после обновления кода сервера */
        fun restart(ctx: Context) {
            if (!running) return
            stop(ctx)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start(ctx) }, 2500)
        }

        /** Запустить/остановить TorrServer при уже работающем сервере */
        fun torrServer(ctx: Context, start: Boolean) {
            if (!running) return
            val intent = Intent(ctx, ServerService::class.java).setAction(if (start) ACTION_TS_START else ACTION_TS_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }
    }
}

package my.torrstream.server

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Один экран: адрес для телевизора, состояние сервера и TorrServer, запуск/остановка,
 * исключение из экономии батареи и журнал. Разметка собирается кодом — экран простой.
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var addressView: TextView
    private lateinit var statusView: TextView
    private lateinit var startStop: Button
    private lateinit var batteryBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var tsInstallBtn: Button
    private lateinit var tsEnabledBox: CheckBox
    private lateinit var tsRemoveBtn: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerService.init(this)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(true)
    }

    private fun buildLayout(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(text(26f, bold = true).apply { text = getString(R.string.app_name) })

        root.addView(text(14f, Color.parseColor("#9A9AA6")).apply {
            text = "Адрес для телевизора (Vidaa и др.) — в одной Wi-Fi сети с телефоном:"
            setPadding(0, dp(18), 0, dp(4))
        })
        addressView = text(24f, Color.parseColor("#FF8C00"), bold = true)
        root.addView(addressView)
        root.addView(text(13f, Color.parseColor("#9A9AA6")).apply {
            text = "Введите его в TorrStream на телевизоре. Если на телефоне работает TorrServer, его адрес (порт 8090) телевизор подставит сам."
            setPadding(0, dp(4), 0, dp(16))
        })

        statusView = text(15f).apply { setPadding(0, 0, 0, dp(16)) }
        root.addView(statusView)

        startStop = Button(this).apply {
            setOnClickListener {
                if (ServerService.running) ServerService.stop(this@MainActivity)
                else ServerService.start(this@MainActivity)
                handler.postDelayed({ refresh() }, 300)
            }
        }
        root.addView(startStop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        batteryBtn = Button(this).apply {
            text = "Разрешить работу в фоне (без экономии батареи)"
            setOnClickListener { requestIgnoreBattery() }
        }
        root.addView(batteryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        updateBtn = Button(this).apply {
            text = "Проверить обновление сервера"
            setOnClickListener { updateServer() }
        }
        root.addView(updateBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // TorrServer — по желанию: можно пользоваться другим (TorrServe, на компьютере)
        root.addView(text(18f, bold = true).apply {
            text = "TorrServer на телефоне"
            setPadding(0, dp(22), 0, dp(4))
        })
        root.addView(text(13f, Color.parseColor("#9A9AA6")).apply {
            text = "Необязательно: телевизор может работать с TorrServer на другом устройстве — он указывается в его настройках."
            setPadding(0, 0, 0, dp(8))
        })
        tsInstallBtn = Button(this).apply {
            text = "Установить TorrServer (~64 МБ)"
            setOnClickListener { installTorrServer() }
        }
        root.addView(tsInstallBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tsEnabledBox = CheckBox(this).apply {
            text = "Запускать вместе с сервером"
            setTextColor(Color.WHITE)
            isChecked = TorrServerInstaller.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                TorrServerInstaller.setEnabled(this@MainActivity, on)
                ServerService.torrServer(this@MainActivity, on)
            }
        }
        root.addView(tsEnabledBox)
        tsRemoveBtn = Button(this).apply {
            text = "Удалить TorrServer"
            setOnClickListener { removeTorrServer() }
        }
        root.addView(tsRemoveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(text(14f, Color.parseColor("#9A9AA6")).apply {
            text = "Журнал"
            setPadding(0, dp(20), 0, dp(6))
        })
        logView = text(11f, Color.parseColor("#C8C8D0")).apply {
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#141419"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            gravity = Gravity.START
        }
        root.addView(logView)

        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            addView(root)
        }
        return logScroll
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val ips = Env.lanAddresses()
        addressView.text = if (ips.isEmpty()) "Нет сети — подключите Wi-Fi"
        else ips.joinToString("\n") { "http://$it:${Env.SERVER_PORT}" }

        val tsProgress = TorrServerInstaller.progress
        val ts = when {
            tsProgress >= 0 -> "Скачиваю… $tsProgress%"
            TorrServerInstaller.lastError != null -> "Ошибка загрузки: ${TorrServerInstaller.lastError}"
            !ServerService.running && TorrServerInstaller.isInstalled(this) ->
                "Установлен ${TorrServerInstaller.version(this) ?: ""}"
            !ServerService.running -> "Не установлен"
            else -> ServerService.torrServerState
        }
        val version = ServerUpdater.currentVersion(this)?.let { v ->
            v + if (ServerUpdater.downloadedAt(this) != 0L) " (обновлён)" else ""
        } ?: "—"
        statusView.text = "Сервер TorrStream: ${ServerService.serverState}\n" +
            "Версия сервера: $version" + (ServerUpdater.status?.let { "\n$it" } ?: "") + "\n" +
            "TorrServer: $ts\n" +
            "Работа в фоне: ${if (ignoringBattery()) "разрешена" else "ограничена экономией батареи"}"

        startStop.text = if (ServerService.running) "Остановить сервер" else "Запустить сервер"
        updateBtn.isEnabled = !ServerUpdater.busy

        val installed = TorrServerInstaller.isInstalled(this)
        val downloading = tsProgress >= 0
        tsInstallBtn.visibility = if (!installed && !downloading) android.view.View.VISIBLE else android.view.View.GONE
        tsEnabledBox.visibility = if (installed) android.view.View.VISIBLE else android.view.View.GONE
        tsRemoveBtn.visibility = if (installed && !downloading) android.view.View.VISIBLE else android.view.View.GONE
        batteryBtn.visibility = if (ignoringBattery()) android.view.View.GONE else android.view.View.VISIBLE

        val log = ServerService.logText()
        if (logView.text.toString() != log) logView.text = log
    }

    private fun updateServer() {
        val ctx = applicationContext
        Thread({
            if (ServerUpdater.checkAndDownload(ctx)) {
                ServerService.log("Скачано обновление сервера")
                // Работает — перезапускаем с новым кодом; нет — новый код возьмётся при запуске
                if (ServerService.running) handler.post { ServerService.restart(ctx) }
            }
        }, "server-update").start()
        handler.postDelayed({ refresh() }, 300)
    }

    private fun installTorrServer() {
        val ctx = applicationContext
        Thread({
            try {
                TorrServerInstaller.install(ctx)
                ServerService.log("TorrServer ${TorrServerInstaller.version(ctx)} установлен")
                if (TorrServerInstaller.enabled(ctx)) ServerService.torrServer(ctx, true)
            } catch (e: Exception) {
                TorrServerInstaller.lastError = e.message ?: e.javaClass.simpleName
                ServerService.log("Ошибка загрузки TorrServer: ${e.message}")
            }
        }, "ts-install").start()
        handler.postDelayed({ refresh() }, 300)
    }

    private fun removeTorrServer() {
        val ctx = applicationContext
        ServerService.torrServer(ctx, false)
        Thread({
            Thread.sleep(1000)   // процесс должен успеть завершиться, иначе файл занят
            TorrServerInstaller.uninstall(ctx)
            ServerService.log("TorrServer удалён")
        }, "ts-remove").start()
    }

    private fun ignoringBattery(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
        }
    }
}

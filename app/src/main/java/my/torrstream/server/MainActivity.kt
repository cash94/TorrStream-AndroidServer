package my.torrstream.server

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Главный экран: адрес для телевизора, запуск/остановка, TorrServer, автозапуск и обслуживание.
 * Состояние сервера с версией — значком в шапке, TorrServer — в своей карточке.
 * Журнал — отдельным экраном (LogActivity).
 *
 * Разметка собирается кодом (Ui.kt). На широком экране — телевизоре или планшете —
 * две колонки, на телефоне одна. Всё, что нажимается, принимает фокус пульта.
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statePill: TextView
    private lateinit var stateDot: View
    private lateinit var addressView: TextView
    private lateinit var startStop: Button
    private lateinit var serverError: TextView
    private lateinit var tsRow: Ui.StatusRow
    private lateinit var updateStatus: TextView
    private lateinit var batteryBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var tsInstallBtn: Button
    private lateinit var tsEnabled: Ui.ToggleRow
    private lateinit var tsRemoveBtn: Button
    private lateinit var tsUpdateBtn: Button
    private lateinit var tsUpdateStatus: TextView
    private lateinit var tsActions: LinearLayout
    private lateinit var tsStartStop: Button
    private lateinit var bootHint: TextView
    private var lastTsActive: Boolean? = null
    private var lastRunning: Boolean? = null

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
        // Автозапуск при открытии — только при настоящем открытии, не при повороте экрана
        if (savedInstanceState == null) {
            Autostart.runOnOpen(this)
            handler.postDelayed({ refresh() }, 300)
        }
        // На телевизоре фокус сразу на главной кнопке — иначе первое нажатие
        // пульта уходит на поиск, куда его поставить
        if (Ui.isTv(this)) startStop.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildLayout(): View {
        val tv = Ui.isTv(this)
        val wide = Ui.wide(this)
        Ui.compact = wide
        // Телевизоры обрезают края кадра (overscan) — поля шире
        val side = dp(if (tv) 40 else 18)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, dp(if (wide) 18 else 22), side, dp(if (wide) 14 else 24))
        }

        // ── Шапка: название и состояние ──
        // На телефоне значок состояния — под названием: с версией сервера в одну
        // строку с ним не помещается
        val header = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.START
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(this, if (wide) 22f else 26f, bold = true).apply { text = getString(R.string.app_name) })
        titles.addView(Ui.text(this, if (wide) 13f else 14f, Ui.MUTED).apply {
            text = "Сервер TorrStream для телевизоров в вашей сети"
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titles, if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, dp(20), Ui.CARD_LINE, dp(1))
            setPadding(dp(14), dp(8), dp(16), dp(8))
        }
        stateDot = View(this)
        pill.addView(stateDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        statePill = Ui.text(this, 14f, bold = true)
        pill.addView(statePill)
        header.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { if (!wide) topMargin = dp(12) })
        root.addView(header)

        // ── Колонки ──
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gap = dp(if (wide) 12 else 16)
        if (wide) {
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cols.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = gap / 2 })
            cols.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = gap / 2 })
            root.addView(cols, Ui.matchWidth(dp(14)))
        } else {
            root.addView(left, Ui.matchWidth(dp(20)))
            root.addView(right, Ui.matchWidth(gap))
        }

        // Адрес
        val addrCard = Ui.card(this, "Адрес для телевизора")
        addressView = Ui.text(this, if (wide) 24f else 26f, Ui.ACCENT, bold = true).apply {
            // Выделять/копировать адрес — только на телефоне: на телевизоре
            // выделяемый текст забирал бы фокус пульта
            if (!tv) setTextIsSelectable(true)
        }
        addrCard.addView(addressView)
        addrCard.addView(Ui.text(this, if (wide) 12f else 13f, Ui.MUTED).apply {
            text = "Введите его в TorrStream на телевизоре (Vidaa и др.) в той же Wi-Fi сети. " +
                "TorrServer отсюда (порт 8090) телевизор подставит сам."
            setPadding(0, dp(6), 0, dp(if (wide) 12 else 16))
            setLineSpacing(0f, 1.15f)
        })
        startStop = Ui.button(this, "Запустить сервер", Ui.Kind.PRIMARY, big = true) {
            if (ServerService.running) ServerService.stop(this) else ServerService.start(this)
            handler.postDelayed({ refresh() }, 300)
        }
        addrCard.addView(startStop, Ui.matchWidth())
        // Не запустился / падает — причина здесь, подробности в журнале
        serverError = Ui.text(this, 13f, Ui.ERR).apply {
            setPadding(dp(4), dp(8), 0, 0)
            visibility = View.GONE
        }
        addrCard.addView(serverError)
        left.addView(addrCard, Ui.matchWidth())

        // TorrServer — по желанию: можно пользоваться другим (TorrServe, на компьютере)
        val tsCard = Ui.card(this, "TorrServer на этом устройстве")
        tsCard.addView(Ui.text(this, if (wide) 12f else 13f, Ui.MUTED).apply {
            text = "Необязательно: телевизор может работать с TorrServer на другом устройстве — он указывается в настройках TorrStream."
            setPadding(0, 0, 0, dp(if (wide) 6 else 8))
            setLineSpacing(0f, 1.15f)
        })
        tsRow = Ui.StatusRow(this, null)
        tsCard.addView(tsRow, Ui.matchWidth(0).apply { bottomMargin = dp(8) })
        tsInstallBtn = Ui.button(this, "Установить TorrServer (~64 МБ)", Ui.Kind.SECONDARY) { installTorrServer() }
        tsCard.addView(tsInstallBtn, Ui.matchWidth())
        // Запуск TorrServer сам по себе, без сервера: остановка сервера его не трогает
        tsStartStop = Ui.button(this, "Запустить TorrServer", Ui.Kind.SECONDARY) {
            if (ServerService.tsActive) ServerService.torrServer(this, false) else ServerService.startTorrServer(this)
            handler.postDelayed({ refresh() }, 300)
        }
        tsCard.addView(tsStartStop, Ui.matchWidth().apply { bottomMargin = dp(10) })
        tsEnabled = Ui.ToggleRow(this, "Запускать вместе с сервером") { on ->
            TorrServerInstaller.setEnabled(this, on)
            ServerService.torrServer(this, on)
        }.apply { checked = TorrServerInstaller.enabled(this@MainActivity) }
        tsCard.addView(tsEnabled, Ui.matchWidth())
        // Обновление и удаление — в один ряд, как в «Обслуживании»
        tsActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tsUpdateBtn = Ui.button(this, "Обновить") { updateTorrServer() }
        tsActions.addView(tsUpdateBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tsRemoveBtn = Ui.button(this, "Удалить", Ui.Kind.DANGER) { removeTorrServer() }
        tsActions.addView(tsRemoveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        tsCard.addView(tsActions, Ui.matchWidth(dp(10)))
        tsUpdateStatus = Ui.text(this, 13f, Ui.MUTED).apply {
            setPadding(dp(4), dp(6), 0, 0)
            visibility = View.GONE
        }
        tsCard.addView(tsUpdateStatus)
        right.addView(tsCard, Ui.matchWidth())

        // Автозапуск
        val autoCard = Ui.card(this, "Автозапуск")
        autoCard.addView(Ui.ToggleRow(this, "Открывать приложение при включении устройства") { on ->
            Autostart.setAppOnBoot(this, on)
            // Android 10+: окно из фона откроется только с разрешением «Поверх других
            // приложений» — сразу ведём туда
            if (on && !Autostart.canOpenFromBackground(this) && !Autostart.requestOverlay(this)) {
                android.widget.Toast.makeText(this, "На устройстве нет экрана этого разрешения — " +
                    "сервер всё равно запустится при включении, если включён его автозапуск", android.widget.Toast.LENGTH_LONG).show()
            }
            refresh()
        }.apply { checked = Autostart.appOnBoot(this@MainActivity) }, Ui.matchWidth())
        bootHint = Ui.text(this, 12f, Ui.WARN).apply {
            text = "Чтобы приложение открывалось само, разрешите ему «Поверх других приложений» " +
                "(Android 10 и новее). Без разрешения при включении запустятся только сервер и TorrServer."
            setPadding(dp(4), dp(6), 0, dp(4))
            setLineSpacing(0f, 1.15f)
            visibility = View.GONE
        }
        autoCard.addView(bootHint)
        autoCard.addView(Ui.ToggleRow(this, "Запускать сервер при открытии приложения") { on ->
            Autostart.setServerOnOpen(this, on)
        }.apply { checked = Autostart.serverOnOpen(this@MainActivity) }, Ui.matchWidth(dp(8)))
        autoCard.addView(Ui.ToggleRow(this, "Запускать TorrServer при открытии приложения") { on ->
            Autostart.setTsOnOpen(this, on)
        }.apply { checked = Autostart.tsOnOpen(this@MainActivity) }, Ui.matchWidth(dp(8)))
        right.addView(autoCard, Ui.matchWidth(gap))

        // Обслуживание
        val toolsCard = Ui.card(this, "Обслуживание")
        // Обновление и журнал — в один ряд: так карточка ниже, и на телевизоре
        // всё помещается на экран
        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        updateBtn = Ui.button(this, "Обновить сервер") { updateServer() }
        toolsRow.addView(updateBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolsRow.addView(Ui.button(this, "Журнал") {
            startActivity(Intent(this, LogActivity::class.java))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        toolsCard.addView(toolsRow, Ui.matchWidth())
        updateStatus = Ui.text(this, 13f, Ui.MUTED).apply { setPadding(dp(4), dp(6), 0, 0) }
        toolsCard.addView(updateStatus)
        batteryBtn = Ui.button(this, "Разрешить работу в фоне") { requestIgnoreBattery() }
        toolsCard.addView(batteryBtn, Ui.matchWidth(dp(10)))
        left.addView(toolsCard, Ui.matchWidth(gap))

        return ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            // Прокрутка идёт за фокусом пульта сама; полоса только мешает
            isVerticalScrollBarEnabled = !tv
            addView(root)
        }
    }

    /** Цвет точки по тексту состояния из ServerService */
    private fun colorOf(state: String) = when {
        state.startsWith("Работает") -> Ui.OK
        state.startsWith("Остановлен") || state.startsWith("Не установлен") || state.startsWith("Установлен") -> Ui.IDLE
        state.startsWith("Не ") || state.startsWith("Падает") || state.startsWith("Ошибка") || state.startsWith("Нет ") -> Ui.ERR
        else -> Ui.WARN
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val running = ServerService.running
        val ips = Env.lanAddresses()
        val addr = if (ips.isEmpty()) "Нет сети — подключите Wi-Fi"
        else ips.joinToString("\n") { "http://$it:${Env.SERVER_PORT}" }
        if (addressView.text.toString() != addr) addressView.text = addr

        val server = ServerService.serverState
        val failed = colorOf(server) == Ui.ERR
        val pillText = when {
            running && server.startsWith("Работает") ->
                "Работает" + (ServerUpdater.currentVersion(this)?.let { " · $it" } ?: "")
            running && failed -> "Ошибка"
            running -> "Запуск…"
            else -> "Остановлен"
        }
        if (statePill.text.toString() != pillText) statePill.text = pillText
        stateDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when {
                failed -> Ui.ERR
                running && server.startsWith("Работает") -> Ui.OK
                running -> Ui.WARN
                else -> Ui.IDLE
            })
        }
        val err = if (failed) server else ""
        if (serverError.text.toString() != err) serverError.text = err
        show(serverError, failed)

        if (lastRunning != running) {
            lastRunning = running
            startStop.text = if (running) "Остановить сервер" else "Запустить сервер"
            Ui.style(startStop, if (running) Ui.Kind.DANGER else Ui.Kind.PRIMARY)
        }

        val tsProgress = TorrServerInstaller.progress
        // Состояние службы — пока работает сервер или TorrServer сам по себе
        val tsLive = running || ServerService.tsActive
        val ts = when {
            tsProgress >= 0 -> "Скачиваю… $tsProgress%"
            TorrServerInstaller.lastError != null -> "Ошибка загрузки: ${TorrServerInstaller.lastError}"
            !tsLive && TorrServerInstaller.isInstalled(this) -> "Установлен ${TorrServerInstaller.version(this) ?: ""}".trim()
            !tsLive -> "Не установлен"
            else -> ServerService.torrServerState
        }
        tsRow.set(ts, if (tsProgress >= 0) Ui.WARN else colorOf(ts))

        val battery = ignoringBattery()

        val upd = ServerUpdater.status ?: ""
        if (updateStatus.text.toString() != upd) updateStatus.text = upd
        updateStatus.visibility = if (upd.isEmpty()) View.GONE else View.VISIBLE

        val installed = TorrServerInstaller.isInstalled(this)
        val downloading = tsProgress >= 0
        val focused = currentFocus
        show(tsInstallBtn, !installed && !downloading)
        show(tsStartStop, installed && !downloading)
        val tsActive = ServerService.tsActive
        if (lastTsActive != tsActive) {
            lastTsActive = tsActive
            tsStartStop.text = if (tsActive) "Остановить TorrServer" else "Запустить TorrServer"
            Ui.style(tsStartStop, if (tsActive) Ui.Kind.DANGER else Ui.Kind.SECONDARY)
        }
        show(bootHint, Autostart.appOnBoot(this) && !Autostart.canOpenFromBackground(this))
        show(tsEnabled, installed)
        show(tsActions, installed && !downloading)
        val tsUpd = TorrServerInstaller.updateStatus ?: ""
        if (tsUpdateStatus.text.toString() != tsUpd) tsUpdateStatus.text = tsUpd
        show(tsUpdateStatus, installed && tsUpd.isNotEmpty())
        show(batteryBtn, !battery)
        // Кнопка под фокусом пульта пропала (TorrServer установился, работу в
        // фоне разрешили) — без этого фокус повис бы в пустоте
        if (focused != null && !focused.isShown) startStop.requestFocus()
    }

    private fun show(v: View, on: Boolean) {
        val want = if (on) View.VISIBLE else View.GONE
        if (v.visibility != want) v.visibility = want
    }

    // Кнопки обновления на время проверки не выключаем, а повторное нажатие
    // пропускаем: выключенная кнопка теряет фокус пульта

    private fun updateServer() {
        if (ServerUpdater.busy) return
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

    private fun updateTorrServer() {
        if (TorrServerInstaller.checking || TorrServerInstaller.progress >= 0) return
        val ctx = applicationContext
        Thread({
            if (TorrServerInstaller.update(ctx)) {
                ServerService.log("TorrServer обновлён до ${TorrServerInstaller.version(ctx)}")
                // Работающий перезапускаем уже с новым файлом. Пауза — чтобы старый
                // процесс успел освободить порт 8090: занятый порт сервис принял бы
                // за чужой TorrServer и свой не запустил. Запущенный сам по себе —
                // снова сам по себе, вместе с сервером — снова с сервером
                val standalone = ServerService.tsStandalone
                if (ServerService.tsActive || (ServerService.running && TorrServerInstaller.enabled(ctx))) {
                    handler.post { ServerService.torrServer(ctx, false) }
                    handler.postDelayed({
                        if (standalone) ServerService.startTorrServer(ctx) else ServerService.torrServer(ctx, true)
                    }, 2500)
                }
            }
        }, "ts-update").start()
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

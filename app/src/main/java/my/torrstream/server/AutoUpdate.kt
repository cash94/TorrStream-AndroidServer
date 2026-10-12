package my.torrstream.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновления сервера раз в полчаса, пока сервер работает.
 *
 * Новая версия есть — ServerUpdater.available (подсказка на кнопке «Обновить сервер»).
 * С галочкой «Устанавливать автоматически» она скачивается сразу, а сервер
 * перезапускается, только когда никто не смотрит: сколько идёт просмотров, отвечает
 * сам сервер (/api/server/streams, только для запросов с этого устройства). Смотрят —
 * пробуем снова через пару минут.
 */
object AutoUpdate {
    private const val CHECK_EVERY_MS = 30 * 60 * 1000L
    private const val FIRST_CHECK_MS = 60 * 1000L
    private const val IDLE_RETRY_MS = 2 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    /** Обновление скачано, ждёт перезапуска сервера */
    @Volatile private var pending = false

    private val check = object : Runnable {
        override fun run() {
            if (!ServerService.running) return
            val ctx = appCtx ?: return
            Thread({ tick(ctx) }, "server-autoupdate").start()
            handler.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    private val idleRetry = Runnable { appCtx?.let { ctx -> Thread({ restartWhenIdle(ctx) }, "server-autoupdate").start() } }

    @Volatile private var appCtx: Context? = null

    /** Сервер запущен — начинаем проверять */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (started) return
        started = true
        handler.postDelayed(check, FIRST_CHECK_MS)
    }

    /** Проверить и, если включена автоустановка, поставить — не дожидаясь очередной проверки */
    fun now(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!ServerService.running) return
        Thread({ tick(ctx.applicationContext) }, "server-autoupdate").start()
    }

    /** Сервер остановлен — перестаём (скачанное возьмётся при следующем запуске) */
    fun stop() {
        started = false
        pending = false
        handler.removeCallbacks(check)
        handler.removeCallbacks(idleRetry)
    }

    private fun tick(ctx: Context) {
        if (pending) { restartWhenIdle(ctx); return }
        val version = ServerUpdater.checkAvailable(ctx) ?: return
        if (!ServerUpdater.autoInstall(ctx)) return
        ServerService.log("Найдено обновление сервера ${ServerUpdater.short(version)} — устанавливаю автоматически")
        if (ServerUpdater.checkAndDownload(ctx)) {
            pending = true
            restartWhenIdle(ctx)
        }
    }

    private fun restartWhenIdle(ctx: Context) {
        if (!ServerService.running) { pending = false; return }
        val streams = activeStreams()
        if (streams > 0) {
            ServerUpdater.pending("Обновление скачано — установится, когда закончится просмотр")
            handler.removeCallbacks(idleRetry)
            handler.postDelayed(idleRetry, IDLE_RETRY_MS)
            return
        }
        pending = false
        ServerService.log("Перезапуск сервера с новой версией")
        handler.post { ServerService.restart(ctx) }
    }

    /** Сколько сейчас смотрят. Сервер старше 1.0.52 такого не умеет — считаем, что никто */
    private fun activeStreams(): Int = try {
        val c = URL("http://127.0.0.1:${Env.SERVER_PORT}/api/server/streams").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        try {
            if (c.responseCode != 200) 0
            else JSONObject(c.inputStream.bufferedReader().readText()).optInt("active", 0)
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        0
    }
}

package my.torrstream.server

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * TorrServer: скачать официальную сборку под Android (YouROK/TorrServer) в память
 * приложения и проверить, не работает ли он уже на localhost:8090 (например, TorrServe —
 * тогда свою копию не запускаем, сервер TorrStream ходит в тот).
 *
 * Версия закреплена — [PINNED_TAG], последний MatriX. С ноября 2026 апстрим выпускает
 * ветку Silo с другим API, с которым TorrStream не работает, поэтому releases/latest
 * не берём. Скачанную раньше не-MatriX версию (Silo) меняем на [PINNED_TAG] перед
 * запуском и при открытии приложения — см. [replaceIfStale].
 */
object TorrServerInstaller {
    /** Закреплённая версия TorrServer: последний MatriX (Silo несовместим с API MatriX) */
    const val PINNED_TAG = "MatriX.150"
    private const val DOWNLOAD_BASE = "https://github.com/YouROK/TorrServer/releases/download/$PINNED_TAG/"

    /** GET с таймаутами; GitHub отдаёт файлы релиза переадресацией — по ней идём сами */
    private fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        var target = url
        repeat(5) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "TorrStream-AndroidServer")
            val code = c.responseCode
            if (code in 300..399) {
                target = c.getHeaderField("Location") ?: throw IOException("Переадресация без адреса")
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            return c
        }
        throw IOException("Слишком много переадресаций")
    }

    @Volatile var progress = -1      // -1 — не качаем
        private set

    fun binary(ctx: Context) = File(File(ctx.filesDir, "torrserver"), "TorrServer")
    fun dataDir(ctx: Context) = File(File(ctx.filesDir, "torrserver"), "data").apply { mkdirs() }
    fun isInstalled(ctx: Context) = binary(ctx).let { it.isFile && it.length() > 0 }
    fun version(ctx: Context): String? =
        ctx.getSharedPreferences("torrserver", Context.MODE_PRIVATE).getString("version", null)

    /** Совместимая ли версия: TorrStream работает только с API MatriX (Silo — другой) */
    fun isMatrix(version: String?) = version?.startsWith("MatriX.") == true

    /** Скачан несовместимый TorrServer (Silo из releases/latest старых версий приложения) */
    fun isStale(ctx: Context) = isInstalled(ctx) && !isMatrix(version(ctx))

    /**
     * Скачанный несовместимый TorrServer меняет на [PINNED_TAG]. true — заменён: если
     * он работал, его надо перезапустить. Блокирующий — звать не с главного потока.
     */
    @Synchronized
    fun replaceIfStale(ctx: Context): Boolean {
        if (!isStale(ctx)) return false
        install(ctx)
        return true
    }

    /** «Запускать TorrServer вместе с сервером» */
    fun enabled(ctx: Context) =
        ctx.getSharedPreferences("torrserver", Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("torrserver", Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()

    fun uninstall(ctx: Context) {
        binary(ctx).delete()
        ctx.getSharedPreferences("torrserver", Context.MODE_PRIVATE).edit().remove("version").apply()
    }

    @Volatile var lastError: String? = null

    /** Версия TorrServer, отвечающего на порту, или null */
    fun runningVersion(): String? = try {
        val c = open("http://127.0.0.1:${Env.TORRSERVER_PORT}/echo", 1500, 1500)
        try { c.inputStream.bufferedReader().readText().trim().take(60) } finally { c.disconnect() }
    } catch (_: IOException) {
        null
    }

    /** Итог последней проверки обновления — показывается в карточке TorrServer */
    @Volatile var updateStatus: String? = null
    @Volatile var checking = false
        private set

    /**
     * «Обновить»: версия закреплена — ставит [PINNED_TAG], если установлена другая.
     * true — заменён: работающий TorrServer надо перезапустить (старый процесс
     * так и работает со старым файлом). Блокирующий — звать не с главного потока.
     */
    fun update(ctx: Context): Boolean {
        checking = true
        updateStatus = "Проверяю обновление…"
        try {
            if (version(ctx) == PINNED_TAG) {
                updateStatus = "Установлена $PINNED_TAG — совместимая с TorrStream версия"
                return false
            }
            updateStatus = "Скачиваю $PINNED_TAG…"
            install(ctx)
            updateStatus = "Установлена $PINNED_TAG"
            return true
        } catch (e: Exception) {
            updateStatus = "Ошибка обновления: ${e.message ?: e.javaClass.simpleName}"
            return false
        } finally {
            checking = false
        }
    }

    /**
     * Скачивает закреплённую версию ([PINNED_TAG]) прямой ссылкой на файл релиза, без
     * GitHub API. Блокирующий — звать не с главного потока. Synchronized: замена при
     * открытии приложения и при запуске TorrServer может прийти разом — один файл.
     */
    @Synchronized
    fun install(ctx: Context) {
        val suffix = Env.torrServerAsset ?: throw IOException("Процессор телефона не поддерживается")
        lastError = null
        progress = 0
        try {
            val tag = PINNED_TAG
            val url = DOWNLOAD_BASE + "TorrServer-$suffix"

            val target = binary(ctx)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "TorrServer.download")
            val conn = open(url, 20000, 60000)
            try {
                val total = conn.contentLength.toLong()
                (conn.inputStream as InputStream).use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress = (done * 100 / total).toInt()
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить файл")
            if (!target.setExecutable(true, false)) throw IOException("Файл нельзя запустить")
            ctx.getSharedPreferences("torrserver", Context.MODE_PRIVATE).edit().putString("version", tag).apply()
        } finally {
            progress = -1
        }
    }
}

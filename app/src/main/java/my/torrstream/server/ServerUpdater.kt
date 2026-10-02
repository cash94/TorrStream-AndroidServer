package my.torrstream.server

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление кода сервера TorrStream без переустановки APK.
 *
 * Код сервера (JS + node_modules, ~3 МБ) лежит в APK (assets/server.zip), а свежая
 * версия — ассетом TorrStream-android-server.zip в публичном релизе #vidaa репозитория
 * cash94/cash94.github.io, рядом со сборками для Windows/Linux/macOS. Скачанный архив
 * берётся вместо встроенного, пока не установлен APK новее него (ServerService.prepareServerApp).
 */
object ServerUpdater {
    private const val RELEASE = "https://api.github.com/repos/cash94/cash94.github.io/releases/tags/%23vidaa"
    private const val ASSET = "TorrStream-android-server.zip"
    private const val PREFS = "server_update"

    @Volatile var status: String? = null
        private set
    @Volatile var busy = false
        private set

    fun downloaded(ctx: Context) = File(ctx.filesDir, "server-update.zip")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Когда скачан архив, который сейчас используется (0 — встроенный из APK) */
    fun downloadedAt(ctx: Context) = prefs(ctx).getLong("downloaded_at", 0L)

    /** Версия сервера из распакованного server.js («TorrStream.1.0.49») */
    fun currentVersion(ctx: Context): String? = try {
        File(Env.serverApp(ctx), "server.js").bufferedReader().use { r ->
            r.readLine()?.let { Regex("'([^']+)'").find(it)?.groupValues?.get(1) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Проверяет релиз и, если ассет новее установленного, скачивает его.
     * Возвращает true, если скачана новая версия (сервер надо перезапустить).
     * Блокирующий — звать не с главного потока.
     */
    fun checkAndDownload(ctx: Context): Boolean {
        busy = true
        status = "Проверяю обновление…"
        try {
            val rel = get(RELEASE).let { JSONObject(String(it)) }
            val assets = rel.optJSONArray("assets") ?: throw IOException("в релизе нет файлов")
            var url: String? = null
            var updated: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == ASSET) {
                    url = a.optString("browser_download_url")
                    updated = a.optString("updated_at")
                }
            }
            if (url == null || updated == null) {
                status = "Обновлений нет: в релизе нет $ASSET"
                return false
            }
            if (updated == prefs(ctx).getString("asset_updated", null) && downloaded(ctx).isFile) {
                status = "Установлена последняя версия"
                return false
            }
            status = "Скачиваю обновление сервера…"
            val data = get(url)
            // Архив должен содержать server.js — иначе это не тот файл
            if (!String(data, 0, minOf(data.size, 4), Charsets.ISO_8859_1).startsWith("PK")) {
                throw IOException("скачан не zip")
            }
            val tmp = File(ctx.filesDir, "server-update.zip.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(downloaded(ctx))) throw IOException("не удалось сохранить")
            prefs(ctx).edit()
                .putString("asset_updated", updated)
                .putLong("downloaded_at", System.currentTimeMillis())
                .apply()
            status = "Скачано обновление от ${updated.take(10)}"
            return true
        } catch (e: Exception) {
            status = "Ошибка обновления: ${e.message}"
            return false
        } finally {
            busy = false
        }
    }

    /** Скачанное обновление отменяется установкой APK новее него */
    fun dropIfOlderThanApk(ctx: Context, apkUpdatedAt: Long) {
        val at = downloadedAt(ctx)
        if (at != 0L && at < apkUpdatedAt) {
            downloaded(ctx).delete()
            prefs(ctx).edit().remove("asset_updated").remove("downloaded_at").apply()
        }
    }

    private fun get(url: String): ByteArray {
        var target = url
        repeat(5) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "TorrStream-AndroidServer")
            val code = c.responseCode
            if (code in 300..399) {
                target = c.getHeaderField("Location") ?: throw IOException("переадресация без адреса")
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            try { return c.inputStream.readBytes() } finally { c.disconnect() }
        }
        throw IOException("слишком много переадресаций")
    }
}

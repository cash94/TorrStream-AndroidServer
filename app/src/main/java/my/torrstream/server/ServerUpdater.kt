package my.torrstream.server

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление кода сервера TorrStream без переустановки APK.
 *
 * Код сервера (JS + node_modules, ~3 МБ) лежит в APK (assets/server.zip), а свежая
 * версия — в папке server/ этого репозитория (cash94/TorrStream-AndroidServer):
 * TorrStream-android-server.zip и version.json с его sha256. Готовит их
 * tools/fetch-binaries.py --server-src. Новый архив — тот, чей sha256 не совпадает
 * с установленным; скачанный проверяется по тому же sha256. Он берётся вместо
 * встроенного, пока не установлен APK новее него (ServerService.prepareServerApp).
 *
 * Пока сервер работает, AutoUpdate раз в полчаса зовёт [checkAvailable]: есть новая
 * версия — [available] и подсказка на кнопке, а с включённой [autoInstall] она
 * скачивается и ставится сама, когда никто не смотрит.
 */
object ServerUpdater {
    private const val BASE = "https://raw.githubusercontent.com/cash94/TorrStream-AndroidServer/main/server/"
    private const val ARCHIVE = "TorrStream-android-server.zip"
    private const val PREFS = "server_update"

    @Volatile var status: String? = null
        private set
    @Volatile var busy = false
        private set
    /** Версия на сервере обновлений, если она новее установленной («TorrStream.1.0.52») */
    @Volatile var available: String? = null
        private set

    fun downloaded(ctx: Context) = File(ctx.filesDir, "server-update.zip")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ставить обновления сервера самому (галочка в «Обслуживании») */
    fun autoInstall(ctx: Context) = prefs(ctx).getBoolean("auto_install", false)
    fun setAutoInstall(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_install", on).apply()

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

    private fun sha256(input: InputStream): String = input.use { s ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** sha256 архива, из которого распакован сервер: скачанного или встроенного */
    private fun installedSha(ctx: Context): String {
        val update = downloaded(ctx)
        if (update.isFile) prefs(ctx).getString("sha256", null)?.let { return it }
        return sha256(ctx.assets.open("server.zip"))
    }

    /**
     * Есть ли новая версия — без скачивания. Возвращает её номер или null (нет новой
     * или проверить не вышло). Блокирующий — звать не с главного потока.
     */
    fun checkAvailable(ctx: Context): String? {
        if (busy) return available
        return try {
            val info = JSONObject(String(get(BASE + "version.json")))
            val sha = info.optString("sha256").lowercase()
            if (sha.length != 64 || sha == installedSha(ctx)) {
                available = null
                null
            } else {
                val version = info.optString("version").ifEmpty { sha.take(8) }
                available = version
                if (!autoInstall(ctx)) status = "Доступна версия ${short(version)} — нажмите «Обновить сервер»"
                version
            }
        } catch (e: Exception) {
            null   // нет сети — проверим в следующий раз, ошибку не показываем
        }
    }

    /** «TorrStream.1.0.52» → «1.0.52» */
    fun short(version: String) = version.removePrefix("TorrStream.")

    /** Скачано, но сервер ещё не перезапущен (ждёт, пока никто не смотрит) */
    fun pending(text: String) {
        status = text
    }

    /**
     * Сверяет version.json из репозитория с установленным архивом и, если тот другой,
     * скачивает новый. Возвращает true, если скачана новая версия (сервер надо
     * перезапустить). Блокирующий — звать не с главного потока.
     */
    fun checkAndDownload(ctx: Context): Boolean {
        busy = true
        status = "Проверяю обновление…"
        try {
            val info = JSONObject(String(get(BASE + "version.json")))
            val sha = info.optString("sha256").lowercase()
            val version = info.optString("version").takeIf { it.isNotEmpty() }
            if (sha.length != 64) throw IOException("нет sha256 в version.json")
            if (sha == installedSha(ctx)) {
                status = "Установлена последняя версия"
                available = null
                return false
            }
            status = "Скачиваю ${version ?: "обновление"}…"
            val data = get(BASE + ARCHIVE)
            // Архив обязан совпасть с version.json: иначе он недокачан или подменён
            // (raw.githubusercontent кэширует файлы по отдельности, и на минуты
            // после выкладки version.json бывает новее архива)
            if (sha256(data.inputStream()) != sha) throw IOException("архив не совпал с version.json, попробуйте через пару минут")
            val tmp = File(ctx.filesDir, "server-update.zip.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(downloaded(ctx))) throw IOException("не удалось сохранить")
            prefs(ctx).edit()
                .putString("sha256", sha)
                .putLong("downloaded_at", System.currentTimeMillis())
                .apply()
            status = "Скачано: ${version ?: sha.take(8)}"
            available = null
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
            prefs(ctx).edit().remove("sha256").remove("downloaded_at").apply()
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

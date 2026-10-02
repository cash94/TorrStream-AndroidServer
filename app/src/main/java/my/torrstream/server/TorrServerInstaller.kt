package my.torrstream.server

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * TorrServer: скачать официальную сборку под Android (YouROK/TorrServer) в память
 * приложения и проверить, не работает ли он уже на localhost:8090 (например, TorrServe —
 * тогда свою копию не запускаем, сервер TorrStream ходит в тот).
 */
object TorrServerInstaller {
    private const val RELEASES = "https://api.github.com/repos/YouROK/TorrServer/releases/latest"

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

    /** Версия TorrServer, отвечающего на порту, или null */
    fun runningVersion(): String? = try {
        val c = open("http://127.0.0.1:${Env.TORRSERVER_PORT}/echo", 1500, 1500)
        try { c.inputStream.bufferedReader().readText().trim().take(60) } finally { c.disconnect() }
    } catch (_: IOException) {
        null
    }

    /** Скачивает последнюю версию. Блокирующий — звать не с главного потока */
    fun install(ctx: Context) {
        val suffix = Env.torrServerAsset ?: throw IOException("Процессор телефона не поддерживается")
        progress = 0
        try {
            val json = open(RELEASES, 20000, 60000).let { c ->
                try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
            }
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets") ?: throw IOException("В релизе нет файлов")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == "TorrServer-$suffix") url = a.optString("browser_download_url")
            }
            if (url == null) throw IOException("Нет сборки TorrServer-$suffix")

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

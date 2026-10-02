package my.torrstream.server

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Пути, адреса и сеть — всё, что нужно для запуска сервера TorrStream на телефоне.
 *
 * Node (сборка Termux под bionic), его библиотеки, ffmpeg и ffprobe (сборка NDK) лежат в
 * APK как lib*.so (jniLibs): система распаковывает их в nativeLibraryDir, откуда
 * разрешён запуск. Код сервера — assets/server.zip. TorrServer скачивается отдельно в
 * память приложения (TorrServerInstaller).
 *
 * Сборки для обычного Linux (Node на musl, ffmpeg на glibc) не годятся: в процессе
 * приложения их убивает фильтр системных вызовов Android (SIGSYS, код выхода 159).
 */
object Env {
    const val SERVER_PORT = 3000
    const val TORRSERVER_PORT = 8090

    fun nativeDir(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir)
    fun node(ctx: Context) = File(nativeDir(ctx), "libnode.so")
    /** Ссылки на библиотеки Node с настоящими именами — для LD_LIBRARY_PATH */
    fun nodeLibDir(ctx: Context) = File(ctx.filesDir, "node-lib")
    /** Распакованный код сервера (assets/server.zip) */
    fun serverApp(ctx: Context) = File(ctx.filesDir, "server-app")
    fun ffmpeg(ctx: Context) = File(nativeDir(ctx), "libffmpeg.so")
    fun ffprobe(ctx: Context) = File(nativeDir(ctx), "libffprobe.so")

    /** Данные сервера: база, каталоги, модули */
    fun serverHome(ctx: Context) = File(ctx.filesDir, "server").apply { mkdirs() }
    /** Сегменты HLS — в кэше: их система может чистить без вреда */
    fun hlsDir(ctx: Context) = File(ctx.cacheDir, "hls").apply { mkdirs() }
    fun serverLog(ctx: Context) = File(ctx.filesDir, "server.log")

    /** IPv4-адреса телефона в локальной сети — по ним телевизор найдёт сервер */
    fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            // Мобильный интернет (rmnet, ccmni…) телевизору недоступен — Wi-Fi и точка доступа впереди
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * DNS-серверы текущей сети. Статическому Node на Android не из чего узнать их самому
     * (нет /etc/resolv.conf) — сервер получает их в TORRSTREAM_DNS (lib/android-dns.js).
     * Общедоступные — запасом, если сеть их не сообщила.
     */
    fun dnsServers(ctx: Context): String {
        val list = ArrayList<String>()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.activeNetwork?.let { net ->
                    cm.getLinkProperties(net)?.dnsServers?.forEach { addr ->
                        addr.hostAddress?.substringBefore('%')?.let { list += it }
                    }
                }
            }
        } catch (_: Exception) { }
        list += listOf("8.8.8.8", "1.1.1.1")
        return list.distinct().joinToString(",")
    }

    /** Сборка TorrServer под процессор телефона (файлы TorrServer-android-* в релизах YouROK) */
    val torrServerAsset: String?
        get() = Build.SUPPORTED_ABIS.firstNotNullOfOrNull {
            when (it) {
                "arm64-v8a" -> "android-arm64"
                "armeabi-v7a" -> "android-arm7"
                "x86_64" -> "android-amd64"
                "x86" -> "android-386"
                else -> null
            }
        }
}

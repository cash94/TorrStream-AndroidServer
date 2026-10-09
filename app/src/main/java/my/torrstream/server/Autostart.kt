package my.torrstream.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Автозапуск — три переключателя на главном экране:
 * - открыть приложение при включении устройства (BootReceiver);
 * - запустить сервер, когда приложение открылось;
 * - запустить TorrServer, когда приложение открылось (сам по себе, без сервера).
 *
 * Запуск при открытии — в MainActivity.onCreate и в BootReceiver: на Android 10+
 * активность из фона система может не открыть (см. [canOpenFromBackground]), а
 * сервер и TorrServer должны подняться после перезагрузки всё равно.
 */
object Autostart {
    private const val PREFS = "autostart"
    private const val APP_ON_BOOT = "app_on_boot"
    private const val SERVER_ON_OPEN = "server_on_open"
    private const val TS_ON_OPEN = "ts_on_open"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun appOnBoot(ctx: Context) = prefs(ctx).getBoolean(APP_ON_BOOT, false)
    fun setAppOnBoot(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(APP_ON_BOOT, on).apply()

    fun serverOnOpen(ctx: Context) = prefs(ctx).getBoolean(SERVER_ON_OPEN, false)
    fun setServerOnOpen(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(SERVER_ON_OPEN, on).apply()

    fun tsOnOpen(ctx: Context) = prefs(ctx).getBoolean(TS_ON_OPEN, false)
    fun setTsOnOpen(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(TS_ON_OPEN, on).apply()

    /** То, что положено сделать при открытии приложения: сервер и/или TorrServer */
    fun runOnOpen(ctx: Context) {
        if (serverOnOpen(ctx) && !ServerService.running) ServerService.start(ctx)
        if (tsOnOpen(ctx) && TorrServerInstaller.isInstalled(ctx) && !ServerService.tsActive) {
            ServerService.startTorrServer(ctx)
        }
    }

    /**
     * Можно ли открыть приложение из фона (после загрузки). С Android 10 система
     * открывает активность из фона, только если у приложения есть разрешение
     * «Поверх других приложений»; без него запуск молча не происходит.
     */
    fun canOpenFromBackground(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)

    /** Экран выдачи разрешения «Поверх других приложений»; false — его на устройстве нет */
    fun requestOverlay(ctx: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }
}

/**
 * Включение устройства: открыть приложение, если включено «Открывать приложение при
 * включении устройства», и сразу запустить то, что положено при открытии, — на
 * случай, если Android 10+ окно из фона не покажет.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return
        val ctx = context.applicationContext
        if (!Autostart.appOnBoot(ctx)) return
        ServerService.init(ctx)
        ServerService.log("Устройство включилось — автозапуск")
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        } catch (e: Exception) {
            ServerService.log("Не удалось открыть приложение: ${e.message}")
        }
        if (!Autostart.canOpenFromBackground(ctx)) {
            ServerService.log("Нет разрешения «Поверх других приложений» — окно может не открыться, сервер запускается без него")
        }
        Autostart.runOnOpen(ctx)
    }
}

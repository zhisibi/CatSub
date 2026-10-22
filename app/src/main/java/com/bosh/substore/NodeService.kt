package com.bosh.substore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class NodeService : Service() {

    companion object {
        const val ACTION_START = "com.bosh.substore.START"
        const val ACTION_STOP = "com.bosh.substore.STOP"
        const val ACTION_EXIT = "com.bosh.substore.EXIT"
        private const val CHANNEL_ID = "substore_node"
        private const val NOTIF_ID = 42

        fun start(ctx: Context) {
            val i = Intent(ctx, NodeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, NodeService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile private var watchdog: Thread? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CatSub 后台服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BootLog.append(this, "NodeService.onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_STOP) {
            NodeRuntime.stop(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_EXIT) {
            // 通知栏「退出」：撤通知 → 同步停 node → 结束整个 App 进程（界面与服务同进程）
            BootLog.append(this, "通知栏退出：彻底结束 CatSub")
            stopForeground(STOP_FOREGROUND_REMOVE)
            Thread {
                NodeRuntime.stopNow(this)
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
            }.start()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        NodeRuntime.start(this)
        startWatchdog()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        val exitPi = PendingIntent.getService(
            this, 1,
            Intent(this, NodeService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return b
            .setContentTitle("CatSub 运行中")
            .setContentText("本机服务 ${NodeRuntime.BASE_URL} · 点按打开")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出", exitPi)
            .setOngoing(true)
            .build()
    }

    /** 进程意外退出时（非手动停止）自动拉起一次；连续失败则放弃，避免死循环耗电。 */
    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread {
            var failures = 0
            while (true) {
                Thread.sleep(5000)
                if (NodeRuntime.isStopRequested()) break
                if (!NodeRuntime.isProcessAlive() && NodeRuntime.state != NodeRuntime.State.STARTING) {
                    failures++
                    if (failures > 5) {
                        NodeRuntime.appendServiceLog(this, "看门狗：连续失败超过 5 次，停止自动重启")
                        break
                    }
                    NodeRuntime.appendServiceLog(this, "看门狗：检测到进程退出，自动重启（第 $failures 次）")
                    NodeRuntime.start(this)
                } else if (NodeRuntime.isProcessAlive()) {
                    failures = 0
                }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        watchdog?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

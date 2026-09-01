package com.xuedi.coder.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xuedi.coder.MainActivity
import com.xuedi.coder.R

/**
 * 【M5-3 骨架版】本地 GGUF 推理前台保活 Service。
 *
 * 为什么一定要前台服务：
 *  - 魅族 Flyme、小米 MIUI、ColorOS 等国产 ROM，应用切后台 3-5 分钟不做白名单就会被杀。
 *  - llama.cpp 3B 模型在 8 核 A710/A715 机型上一次解码跑 60-120s 很常见，被杀 = 推理白跑。
 *  - startForeground + 常驻通知是目前唯一 Android 官方允许的"不被杀"通用方案。
 *
 *  v0.4b 骨架期功能：
 *    1) Service 被启动就立刻 startForeground(ID, notification)；
 *    2) 通知文案显示"骨架期：后台保活开启中，真推理 M5-4 接入。"
 *    3) 点通知 PendingIntent → 返回 MainActivity（聊天页）。
 *
 *  M5-4 真推理接入后：
 *    - 把 Service 改成 bindService / 或通过 App.instance 拿到 LlamaJniEngine，
 *    - 每次开始推理前启动 Service（保证 5 分钟以上长推理不会被系统回收），
 *    - 推理结束时调 stopForeground(STOP_FOREGROUND_DETACH) + stopSelf() 把前台身份撤下，
 *      仅保留在缓存 Service 状态（用户下次打开 APP 秒级回连）。
 */
class InferenceForegroundService : Service() {

    companion object {
        private const val TAG = "InferenceFGService"
        private const val NOTIF_ID = 9527
        private const val CHANNEL_ID = "inference_fg"
        private const val EXTRA_CMD = "cmd"
        private const val CMD_START = "start"
        private const val CMD_STOP = "stop"

        /** 便捷：启动前台保活（通常在开始真推理前调用一次） */
        fun start(ctx: Context) {
            val i = Intent(ctx, InferenceForegroundService::class.java)
                .putExtra(EXTRA_CMD, CMD_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        /** 便捷：停止前台保活（推理结束 / 用户手动取消） */
        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, InferenceForegroundService::class.java)
                    .putExtra(EXTRA_CMD, CMD_STOP)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.getStringExtra(EXTRA_CMD) ?: CMD_START
        when (cmd) {
            CMD_START -> {
                // 5s 内必须调 startForeground，否则 Android 12+ 会 RemoteServiceException
                startForeground(NOTIF_ID, buildNotification(prompt = "🧩 骨架模式开启：后台保活通道已建立。\n真推理接入后显示解码进度"))
            }
            CMD_STOP -> {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
                stopSelf(startId)
            }
        }
        // START_STICKY：Flyme 真杀之后，当内存有空闲时系统会尝试重新拉起这个 Service（Intent=null）
        // 重启后再触发一次 startForeground，保持保活通道。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =================================================================
    // 内部工具
    // =================================================================

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "AI 推理前台保活",
                    NotificationManager.IMPORTANCE_LOW, // LOW=不叮不响，仅常驻
                ).apply {
                    description = "本地 GGUF 模型大解码过程中保持常驻通知，避免系统回收进程"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(prompt: String): Notification {
        val clickIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)       // 常驻，用户手动不能划掉
            .setShowWhen(false)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🧠 AI编程助手 · 本地推理通道")
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .setPriority(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    NotificationManager.IMPORTANCE_LOW else NotificationCompat.PRIORITY_LOW
            )
            .setContentIntent(clickIntent)
        // Android 13+ 通知权限 POST_NOTIFICATIONS 由用户授予后才会真显示；不授予也没事，
        // startForeground 仍然生效（后台策略会看 ForegroundServiceState，不会杀）。
        return builder.build()
    }
}

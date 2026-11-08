package com.hanwoo.stockreport

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 1시간마다 index.json 을 확인해 새 리포트가 올라오면 알림. */
class ReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val latest = Repo.index(ctx).firstOrNull() ?: return Result.success()
            val seen = Prefs.seen(ctx)
            if (seen == null) {
                Prefs.setSeen(ctx, latest)
            } else if (latest > seen) {
                Notifier.notifyReport(ctx, Repo.report(ctx, latest, true))
                Prefs.setSeen(ctx, latest)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ReportWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("report-check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object Notifier {
    private const val CH = "reports"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "새 리포트", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @SuppressLint("MissingPermission")
    fun notifyReport(ctx: Context, r: Report) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val interest = r.buy.filter { it.verdict == "관심" }.map { it.name }
        val text = "매수 ${r.buy.size} (관심 ${interest.size}) · 매도 ${r.sell.size}"
        val big = buildString {
            append(text)
            if (interest.isNotEmpty()) append("\n⭐ ").append(interest.joinToString(", "))
            r.summary?.let { append("\n📒 추적 보유 ${it.openN} 평균 ${pct(it.openAvg)}") }
        }
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("📊 ${r.date} 주식 리포트")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(r.date.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}

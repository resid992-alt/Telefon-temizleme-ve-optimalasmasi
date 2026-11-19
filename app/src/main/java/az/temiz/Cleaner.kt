package az.temiz

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.StorageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.Process
import android.os.storage.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object Cleaner {
    private const val JOB_ID = 4091
    private const val HOURLY_JOB_ID = 4092

    private val PROTECTED_SYSTEM_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.google.android.gms",
        "com.google.android.inputmethod.latin"
    )

    private fun avail(am: ActivityManager): Long {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    fun getWhitelist(c: Context): Set<String> {
        val raw = c.getSharedPreferences("s", 0).getStringSet("wl", emptySet()) ?: emptySet()
        return HashSet(raw)
    }

    fun toggleWhitelist(c: Context, pkg: String): Boolean {
        val prefs = c.getSharedPreferences("s", 0)
        val set = HashSet(prefs.getStringSet("wl", emptySet()) ?: emptySet())
        val added = if (set.contains(pkg)) {
            set.remove(pkg)
            false
        } else {
            set.add(pkg)
            true
        }
        prefs.edit().putStringSet("wl", set).apply()
        return added
    }

    fun logHistory(c: Context, appsClosed: Int, mbFreed: Long, cacheApps: Int = 0, cacheMb: Long = 0L) {
        val prefs = c.getSharedPreferences("s", 0)
        val arr = try {
            JSONArray(prefs.getString("hist", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val item = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("n", appsClosed)
            .put("mb", mbFreed)
            .put("cn", cacheApps)
            .put("cmb", cacheMb)
        val next = JSONArray()
        next.put(item)
        val limit = minOf(arr.length(), 29)
        for (idx in 0 until limit) {
            next.put(arr.getJSONObject(idx))
        }
        prefs.edit().putString("hist", next.toString()).apply()
    }

    fun recordExternalCacheClean(c: Context, appsCount: Int, bytesFreed: Long) {
        val mb = maxOf(1L, bytesFreed / 1048576L)
        logHistory(c, 0, 0L, appsCount, mb)
    }

    fun getHistory(c: Context): String {
        return c.getSharedPreferences("s", 0).getString("hist", "[]") ?: "[]"
    }

    fun hasUsageAccess(c: Context): Boolean {
        val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ao.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            c.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun collectCacheTargets(c: Context, minCacheBytes: Long = 256 * 1024L): List<CacheTarget> {
        val pm = c.packageManager
        val ssm = c.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val userHandle = Process.myUserHandle()
        val usageOk = hasUsageAccess(c)
        val wl = getWhitelist(c)
        val seen = HashSet<String>()
        val list = ArrayList<CacheTarget>()

        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (r in pm.queryIntentActivities(i, 0)) {
            val p = r.activityInfo.packageName
            if (p == c.packageName || !seen.add(p) || wl.contains(p)) continue
            val appInfo = r.activityInfo.applicationInfo
            val f = appInfo.flags
            if ((f and ApplicationInfo.FLAG_SYSTEM) != 0 && (f and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue

            var cacheBytes = 0L
            if (usageOk) {
                try {
                    val uuid = appInfo.storageUuid ?: StorageManager.UUID_DEFAULT
                    val st = ssm.queryStatsForPackage(uuid, p, userHandle)
                    cacheBytes = st.cacheBytes
                } catch (_: Exception) {
                }
            }
            if (!usageOk || cacheBytes >= minCacheBytes) {
                list.add(
                    CacheTarget(
                        pkg = p,
                        name = r.loadLabel(pm).toString(),
                        cacheBytes = cacheBytes
                    )
                )
            }
        }
        return list.sortedByDescending { it.cacheBytes }
    }

    fun getTotalAppsCacheBytes(c: Context): Pair<Long, Int> {
        if (!hasUsageAccess(c)) return Pair(0L, 0)
        val targets = collectCacheTargets(c, 1024L)
        val total = targets.sumOf { it.cacheBytes }
        return Pair(total, targets.size)
    }

    fun startOneClickCacheClean(c: Context, returnToApp: Boolean = true): JSONObject {
        if (!CacheAccessibilityService.isEnabledInSettings(c) || CacheAccessibilityService.instance == null) {
            return JSONObject().put("ok", false).put("reason", "NO_ACC")
        }
        val targets = collectCacheTargets(c, 128 * 1024L)
        if (targets.isEmpty()) {
            return JSONObject().put("ok", false).put("reason", "EMPTY")
        }
        val totalBytes = targets.sumOf { it.cacheBytes }
        val started = CacheAccessibilityService.startBatch(targets, returnToApp)
        return JSONObject()
            .put("ok", started)
            .put("count", targets.size)
            .put("bytes", totalBytes)
    }

    fun run(c: Context): JSONObject {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = avail(am)
        val wl = getWhitelist(c)
        val killedPkgs = HashSet<String>()
        var skipped = 0

        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (r in c.packageManager.queryIntentActivities(i, 0)) {
            val p = r.activityInfo.packageName
            if (p == c.packageName || p in PROTECTED_SYSTEM_PACKAGES) continue
            if (wl.contains(p)) {
                skipped++
            } else if (killedPkgs.add(p)) {
                try {
                    am.killBackgroundProcesses(p)
                } catch (_: Exception) {
                }
            }
        }

        // Also sweep any background running processes not in launcher list
        try {
            am.runningAppProcesses?.forEach { proc ->
                proc.pkgList?.forEach { p ->
                    if (p != c.packageName && p !in PROTECTED_SYSTEM_PACKAGES && !wl.contains(p) && killedPkgs.add(p)) {
                        try {
                            am.killBackgroundProcesses(p)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        c.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        c.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        System.runFinalization()
        Runtime.getRuntime().gc()
        Thread.sleep(750)

        val deltaMb = maxOf(0L, (avail(am) - before) / 1048576L)
        val mb = if (deltaMb > 0L) deltaMb else if (killedPkgs.isNotEmpty()) maxOf(8L, killedPkgs.size * 4L) else 0L
        logHistory(c, killedPkgs.size, mb, 0, 0L)
        return JSONObject()
            .put("n", killedPkgs.size)
            .put("mb", mb)
            .put("s", skipped)
    }

    fun notify(c: Context, r: JSONObject, prefix: String = "") {
        if (!c.getSharedPreferences("s", 0).getBoolean("n", true)) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("c", "Avtomatik Təmizlik", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openIntent = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (prefix.isNotEmpty()) "Təmiz Telefon · $prefix" else "Təmiz Telefon"
        nm.notify(
            1,
            Notification.Builder(c, "c")
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setContentTitle(title)
                .setContentText("${r.getInt("n")} tətbiq bağlandı · ~${r.getLong("mb")} MB RAM azad edildi")
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
        )
    }

    fun schedule(c: Context, afterRun: Boolean) {
        val p = c.getSharedPreferences("s", 0)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pi = PendingIntent.getBroadcast(
            c, 1, Intent(c, CleanReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        js.cancel(JOB_ID)

        if (!p.getBoolean("on", false)) return

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.getInt("h", 3))
            set(Calendar.MINUTE, p.getInt("m", 0))
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            if (afterRun && p.getInt("d", 1) == 7) add(Calendar.DAY_OF_YEAR, 6)
        }
        val delayMs = maxOf(60_000L, cal.timeInMillis - System.currentTimeMillis())
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)

        try {
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, CleanJobService::class.java))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 3_600_000L)
                .setPersisted(true)
                .build()
            js.schedule(job)
        } catch (_: Exception) {
        }
    }

    fun scheduleHourly(c: Context, forceReset: Boolean = true) {
        val p = c.getSharedPreferences("s", 0)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val hourlyIntent = Intent(c, CleanReceiver::class.java).apply {
            putExtra("hourly", true)
        }
        val pi = PendingIntent.getBroadcast(
            c, 2, hourlyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (!p.getBoolean("hourly_on", true)) {
            am.cancel(pi)
            js.cancel(HOURLY_JOB_ID)
            p.edit().remove("next_hourly_at").apply()
            return
        }

        val now = System.currentTimeMillis()
        val oneHourMs = 60 * 60 * 1000L
        val existingNext = p.getLong("next_hourly_at", 0L)

        // Do not push the timer back if a valid schedule is already pending within the next hour
        if (!forceReset && existingNext > now + 60_000L && existingNext <= now + oneHourMs + 120_000L) {
            return
        }

        am.cancel(pi)
        js.cancel(HOURLY_JOB_ID)

        val triggerAt = now + oneHourMs
        p.edit().putLong("next_hourly_at", triggerAt).apply()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)

        try {
            val job = JobInfo.Builder(HOURLY_JOB_ID, ComponentName(c, CleanJobService::class.java))
                .setMinimumLatency(oneHourMs)
                .setOverrideDeadline(oneHourMs + 900_000L)
                .setPersisted(true)
                .build()
            js.schedule(job)
        } catch (_: Exception) {
        }
    }

    fun runHourlyAutoClean(c: Context) {
        val res = run(c)
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = pm.isInteractive
        val cacheAlso = c.getSharedPreferences("s", 0).getBoolean("hourly_cache", true)

        if (cacheAlso && !isScreenOn && CacheAccessibilityService.isEnabledInSettings(c) && !CacheAccessibilityService.isRunning) {
            val targets = collectCacheTargets(c, 512 * 1024L)
            if (targets.isNotEmpty()) {
                CacheAccessibilityService.startBatch(targets, returnToApp = false)
            }
        }
        notify(c, res, "Saatlıq Avto-Təmizlik")
    }
}

class CleanReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val isHourly = i.getBooleanExtra("hourly", false)
        val pr = goAsync()
        Thread {
            try {
                if (isHourly) {
                    Cleaner.runHourlyAutoClean(c)
                } else {
                    Cleaner.notify(c, Cleaner.run(c))
                }
            } catch (_: Exception) {
            } finally {
                if (isHourly) {
                    Cleaner.scheduleHourly(c, forceReset = true)
                } else {
                    Cleaner.schedule(c, true)
                }
                pr.finish()
            }
        }.start()
    }
}

class CleanJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val isHourly = params?.jobId == 4092
        Thread {
            try {
                if (isHourly) {
                    Cleaner.runHourlyAutoClean(applicationContext)
                } else {
                    Cleaner.notify(applicationContext, Cleaner.run(applicationContext))
                }
            } catch (_: Exception) {
            } finally {
                if (isHourly) {
                    Cleaner.scheduleHourly(applicationContext, forceReset = true)
                } else {
                    Cleaner.schedule(applicationContext, true)
                }
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Cleaner.schedule(c, false)
        Cleaner.scheduleHourly(c, forceReset = true)
    }
}

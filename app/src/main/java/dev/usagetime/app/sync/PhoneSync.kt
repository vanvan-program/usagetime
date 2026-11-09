package dev.usagetime.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.work.*
import dev.usagetime.app.UsageTimeApplication
import dev.usagetime.app.data.CategoryPreferences
import dev.usagetime.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PhoneSyncConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val pairingKey: String = PhoneCrypto.newKey(),
    val deviceId: String = UUID.randomUUID().toString(),
    val deviceName: String = "我的手机"
)

internal class PhoneSyncSettings(private val context: Context) {
    private val file get() = AtomicFile(File(context.filesDir, "phone-sync.settings"))
    private fun localKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("phone-sync", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("phone-sync", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): PhoneSyncConfig {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return PhoneSyncConfig()
        val bytes = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return PhoneSyncConfig(json.getBoolean("enabled"), json.getString("url"), json.getString("user"),
            json.getString("password"), json.getString("pairingKey"), json.getString("deviceId"), json.getString("deviceName"))
    }
    fun save(config: PhoneSyncConfig) {
        PhoneCrypto.key(config.pairingKey)
        if (config.enabled) rootUrl(config.url)
        require(config.deviceName.isNotBlank() && config.deviceName.length <= 100) { "设备名称需为 1–100 字" }
        val json = JSONObject().put("enabled", config.enabled).put("url", config.url.trim()).put("user", config.user)
            .put("password", config.password).put("pairingKey", config.pairingKey.trim())
            .put("deviceId", config.deviceId).put("deviceName", config.deviceName)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localKey())
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        schedule(context, config.enabled)
    }
    companion object {
        fun rootUrl(text: String): URI {
            val uri = URI(text.trim().trimEnd('/') + "/")
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "请输入 HTTPS WebDAV 地址，账号密码请填在独立输入框"
            }
            return uri.resolve("usagetime-v1/")
        }
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork("phone-sync-periodic")
                manager.cancelUniqueWork("phone-sync-now")
                return
            }
            manager.enqueueUniquePeriodicWork("phone-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PhoneSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                    .build())
        }
        fun now(context: Context, republish: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork("phone-sync-now", if (republish) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PhoneSyncWorker>().setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf("republish" to republish)).build())
        }
    }
}

internal object PhoneSync {
    private val mutex = Mutex()
    suspend fun upload(context: Context, republish: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val config = PhoneSyncSettings(context).load()
            if (!config.enabled) return@withContext
            val status = context.getSharedPreferences("phone-sync-status", Context.MODE_PRIVATE)
            status.edit().putString("message", "正在同步…").apply()
            val root = PhoneSyncSettings.rootUrl(config.url)
            val secret = PhoneCrypto.key(config.pairingKey)
            val dav = PhoneDav(root, config.user, config.password)
            fun put(path: String, bytes: ByteArray) {
                val limit = if (path.endsWith(".utmanifest")) 4 * 1024 * 1024 else 8 * 1024 * 1024
                check(bytes.size + 28 <= limit) { "单个同步文件超出大小上限，请保留完整本地备份" }
                dav.put(path, PhoneCrypto.encrypt(secret, path, bytes))
            }
            dav.mkdir("")
            dav.mkdir("${config.deviceId}/")
            val store = (context.applicationContext as UsageTimeApplication).store
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val first = store.first()?.let { Dates.date(it, zone) } ?: today
            val categories = CategoryPreferences(context).read()
            val ledgerFile = File(context.filesDir, "phone-sync-ledger.json")
            val scope = MessageDigest.getInstance("SHA-256").digest("${config.url}|${config.user}|${config.pairingKey}|${zone.id}".toByteArray()).joinToString("") { "%02x".format(it) }
            val ledger = runCatching { JSONObject(ledgerFile.readText()) }.getOrDefault(JSONObject())
            val remoteMissing = dav.request("${config.deviceId}.utmanifest", "HEAD") == 404
            val hashes = if (!republish && !remoteMissing && ledger.optString("scope") == scope) ledger.optJSONObject("days") ?: JSONObject() else JSONObject()
            val available = JSONObject()
            var day = first
            while (day <= today) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val metrics = JSONArray()
                val start = Dates.midnight(day, zone)
                val end = minOf(Dates.midnight(day.plusDays(1), zone), System.currentTimeMillis())
                for (metric in UsageMetric.entries) {
                    val report = store.aggregate(Window(start, end), zone, Grain.DAY, metric, null, true)
                    val quality = report.quality
                    val apps = JSONArray()
                    report.apps.forEach { app ->
                        val appQuality = report.appQuality[app.app]
                        val category = categories[app.app] ?: AppCategory.OTHER
                        apps.put(JSONObject().put("app", app.app).put("label", store.cachedLabel(app.app) ?: app.app)
                            .put("category", category.label).put("millis", app.millis)
                            .put("confirmed", appQuality?.confirmed ?: 0).put("inferred", appQuality?.inferred ?: 0))
                    }
                    metrics.put(JSONObject().put("metric", metric.name).put("totalMillis", report.total)
                        .put("confirmed", quality?.confirmed ?: 0).put("inferred", quality?.inferred ?: 0)
                        .put("unknown", quality?.unknown ?: (end - start)).put("provisional", quality?.provisional ?: 0)
                        .put("observed", report.coveredMillis).put("apps", apps))
                }
                val json = JSONObject().put("format", "usagetime.day").put("version", 1)
                    .put("deviceId", config.deviceId).put("date", day.toString()).put("zone", zone.id)
                    .put("start", start).put("end", end).put("metrics", metrics)
                val body = json.toString().toByteArray(Charsets.UTF_8)
                val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                val path = "${config.deviceId}/$day.$hash.utday"
                if (hashes.optString(day.toString()) != hash) {
                    put(path, body)
                    hashes.put(day.toString(), hash)
                    val atomic = AtomicFile(ledgerFile)
                    val stream = atomic.startWrite()
                    try {
                        stream.write(JSONObject().put("scope", scope).put("days", hashes).toString().toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(stream)
                    } catch (error: Exception) { atomic.failWrite(stream); throw error }
                }
                available.put(day.toString(), hash)
                day = day.plusDays(1)
            }
            val manifest = JSONObject().put("format", "usagetime.manifest").put("version", 1)
                .put("deviceId", config.deviceId).put("deviceName", config.deviceName).put("zone", zone.id)
                .put("updatedAt", Instant.now().toString()).put("days", available)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            put("${config.deviceId}.utmanifest", manifest.toString().toByteArray(Charsets.UTF_8))
            status.edit().putLong("lastSuccess", System.currentTimeMillis()).putString("message", "同步完成，共 ${available.length()} 天").apply()
        }
    }
}

class PhoneSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        PhoneSync.upload(applicationContext, inputData.getBoolean("republish", false))
        if (DesktopDataSettings.enabled(applicationContext)) {
            runCatching { DesktopSync.pull(applicationContext) }
        }
        Result.success()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        applicationContext.getSharedPreferences("phone-sync-status", Context.MODE_PRIVATE).edit()
            .putString("message", error.message?.take(160) ?: "同步失败，下次自动重试").apply()
        if (runAttemptCount < 4) Result.retry() else Result.failure()
    }
}

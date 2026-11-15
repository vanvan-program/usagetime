package dev.usagetime.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.edit
import androidx.work.*
import dev.usagetime.app.UsageTimeApplication
import dev.usagetime.app.data.CategoryPreferences
import dev.usagetime.app.data.PhoneSyncHelpers
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

internal class InvalidSyncSettingsException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

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
        return try {
            val bytes = file.readFully()
            if (bytes.size < 13) throw InvalidSyncSettingsException("无法读取同步配置，请在设置中重新保存")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            PhoneSyncConfig(json.getBoolean("enabled"), json.getString("url"), json.getString("user"),
                json.getString("password"), json.getString("pairingKey"), json.getString("deviceId"), json.getString("deviceName"))
        } catch (error: InvalidSyncSettingsException) {
            throw error
        } catch (error: Exception) {
            throw InvalidSyncSettingsException("无法读取同步配置，请在设置中重新保存", error)
        }
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
        SyncSupport.writeAtomicBytes(
            file,
            cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        )
        schedule(context, config.enabled)
    }
    companion object {
        fun rootUrl(text: String): URI = SyncSupport.resolveWebDavRoot(text, "usagetime-v1")
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
            status.edit { putString("message", "正在同步…") }
            val root = PhoneSyncSettings.rootUrl(config.url)
            val secret = PhoneCrypto.key(config.pairingKey)
            val dav = PhoneDav(root, config.user, config.password)
            var dirsVerified = false
            fun ensureDirs() {
                if (dirsVerified) return
                dav.mkdir("")
                dav.mkdir("${config.deviceId}/")
                dirsVerified = true
            }
            fun put(path: String, bytes: ByteArray) {
                val limit = if (path.endsWith(".utmanifest")) 4 * 1024 * 1024 else 8 * 1024 * 1024
                check(bytes.size + 28 <= limit) { "单个同步文件超出大小上限，请保留完整本地备份" }
                val encrypted = PhoneCrypto.encrypt(secret, path, bytes)
                try {
                    dav.put(path, encrypted)
                } catch (error: IllegalStateException) {
                    if (!dirsVerified) {
                        ensureDirs()
                        dav.put(path, encrypted)
                    } else {
                        throw error
                    }
                }
            }
            val manifestPath = "${config.deviceId}.utmanifest"
            val manifestHead = dav.request(manifestPath, "HEAD")
            check(manifestHead in 200..299 || manifestHead == 404) { "WebDAV 状态检查失败（HTTP $manifestHead）" }
            val remoteMissing = manifestHead == 404
            if (remoteMissing || republish) ensureDirs()

            val store = (context.applicationContext as UsageTimeApplication).store
            store.ensureNamesLoaded()
            val syncWatermark = store.syncWatermark()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val first = store.first()?.let { Dates.date(it, zone) } ?: today
            val categories = CategoryPreferences(context).read()
            val categoryHash = PhoneSyncHelpers.categoryDigest(categories)
            val ledgerFile = File(context.filesDir, "phone-sync-ledger.json")
            val scope = PhoneSyncHelpers.sha256Hex(
                "v2|${config.url}|${config.user}|${config.pairingKey}|${config.deviceId}|${zone.id}".toByteArray(Charsets.UTF_8)
            )
            val ledger = runCatching { JSONObject(ledgerFile.readText()) }.getOrDefault(JSONObject())
            val scopeMatches = ledger.optString("scope") == scope
            val categoryMatches = scopeMatches && ledger.optString("categoryHash") == categoryHash
            val labelsMatch = scopeMatches && ledger.optLong("labelsVersion", -1L) == syncWatermark.labelsVersion
            val syncedEndDate = if (scopeMatches) {
                ledger.optString("syncedEndDate", "").takeIf { it.isNotEmpty() }?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                }
            } else null
            val prevSeq = if (scopeMatches) ledger.optLong("watermarkSeq", -1L) else -1L
            val dirtyMillis = if (scopeMatches && prevSeq >= 0L) store.dirtyFrom(prevSeq) else null
            val skipBefore = PhoneSyncHelpers.skipBeforeDate(
                first = first,
                today = today,
                republish = republish,
                remoteMissing = remoteMissing,
                scopeMatches = scopeMatches,
                categoryMatches = categoryMatches,
                labelsMatch = labelsMatch,
                syncedEndDate = syncedEndDate,
                dirtyMillis = dirtyMillis,
                zone = zone
            )
            val workingHashes = if (!republish && !remoteMissing && scopeMatches) {
                ledger.optJSONObject("days") ?: JSONObject()
            } else {
                JSONObject()
            }
            val available = JSONObject()
            val availableMap = LinkedHashMap<String, String>()
            var changedCount = 0
            var dirtyLedger = false
            fun writeLedger(
                days: JSONObject,
                completedWatermarkSeq: Long? = null,
                completedLabelsVersion: Long? = null,
                completedEndDate: String? = null,
                completedManifestMeta: String? = null
            ) {
                val payload = JSONObject()
                    .put("scope", scope)
                    .put("categoryHash", categoryHash)
                    .put("days", days)
                if (completedWatermarkSeq != null && completedLabelsVersion != null && completedEndDate != null && completedManifestMeta != null) {
                    payload.put("watermarkSeq", completedWatermarkSeq)
                        .put("labelsVersion", completedLabelsVersion)
                        .put("syncedEndDate", completedEndDate)
                        .put("manifestMeta", completedManifestMeta)
                }
                SyncSupport.writeAtomicBytes(
                    AtomicFile(ledgerFile),
                    payload.toString().toByteArray(Charsets.UTF_8)
                )
            }

            var day = first
            try {
                while (day <= today) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val dayKey = day.toString()
                    val existingHash = workingHashes.optString(dayKey, "")
                    if (day < skipBefore && existingHash.isNotEmpty()) {
                        available.put(dayKey, existingHash)
                        availableMap[dayKey] = existingHash
                        day = day.plusDays(1)
                        continue
                    }
                    val metrics = JSONArray()
                    val start = Dates.midnight(day, zone)
                    val end = minOf(Dates.midnight(day.plusDays(1), zone), System.currentTimeMillis())
                    for (metric in UsageMetric.entries) {
                        val report = store.aggregate(Window(start, end), zone, Grain.DAY, metric, null, true)
                        val quality = report.quality
                        val apps = JSONArray()
                        for (app in report.apps) {
                            val appQuality = report.appQuality[app.app]
                            val category = categories[app.app] ?: AppCategory.OTHER
                            val label = store.cachedLabel(app.app) ?: store.warmLabel(app.app)
                            apps.put(JSONObject().put("app", app.app).put("label", label)
                                .put("category", category.label).put("millis", app.millis)
                                .put("confirmed", appQuality?.confirmed ?: 0).put("inferred", appQuality?.inferred ?: 0))
                        }
                        metrics.put(JSONObject().put("metric", metric.name).put("totalMillis", report.total)
                            .put("confirmed", quality?.confirmed ?: 0).put("inferred", quality?.inferred ?: 0)
                            .put("unknown", quality?.unknown ?: (end - start)).put("provisional", quality?.provisional ?: 0)
                            .put("observed", report.coveredMillis).put("apps", apps))
                    }
                    val json = JSONObject().put("format", "usagetime.day").put("version", 1)
                        .put("deviceId", config.deviceId).put("date", dayKey).put("zone", zone.id)
                        .put("start", start).put("end", end).put("metrics", metrics)
                    val body = json.toString().toByteArray(Charsets.UTF_8)
                    val hash = PhoneSyncHelpers.sha256Hex(body)
                    if (existingHash != hash) {
                        val path = "${config.deviceId}/$dayKey.$hash.utday"
                        put(path, body)
                        workingHashes.put(dayKey, hash)
                        changedCount++
                        dirtyLedger = true
                        if (changedCount % 16 == 0) {
                            writeLedger(days = workingHashes)
                            dirtyLedger = false
                        }
                    }
                    available.put(dayKey, hash)
                    availableMap[dayKey] = hash
                    day = day.plusDays(1)
                }
                val nextManifestMeta = PhoneSyncHelpers.manifestDigest(config.deviceId, config.deviceName, zone.id, availableMap)
                val prevManifestMeta = if (scopeMatches) ledger.optString("manifestMeta", "") else ""
                val uploadManifest = PhoneSyncHelpers.shouldUploadManifest(republish, remoteMissing, changedCount, prevManifestMeta, nextManifestMeta)
                if (uploadManifest) {
                    val manifest = JSONObject().put("format", "usagetime.manifest").put("version", 1)
                        .put("deviceId", config.deviceId).put("deviceName", config.deviceName).put("zone", zone.id)
                        .put("updatedAt", Instant.now().toString()).put("days", available)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    put(manifestPath, manifest.toString().toByteArray(Charsets.UTF_8))
                }
                if (dirtyLedger || uploadManifest || prevSeq != syncWatermark.seq ||
                    ledger.optString("syncedEndDate") != today.toString() ||
                    ledger.optLong("labelsVersion", -1L) != syncWatermark.labelsVersion ||
                    ledger.optString("categoryHash") != categoryHash ||
                    ledger.optString("scope") != scope
                ) {
                    writeLedger(
                        days = available,
                        completedWatermarkSeq = syncWatermark.seq,
                        completedLabelsVersion = syncWatermark.labelsVersion,
                        completedEndDate = today.toString(),
                        completedManifestMeta = nextManifestMeta
                    )
                    dirtyLedger = false
                }
                status.edit { putLong("lastSuccess", System.currentTimeMillis()).putString("message", "同步完成，共 ${available.length()} 天") }
            } finally {
                if (dirtyLedger) {
                    runCatching { writeLedger(days = workingHashes) }
                }
            }
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
    } catch (error: InvalidSyncSettingsException) {
        applicationContext.getSharedPreferences("phone-sync-status", Context.MODE_PRIVATE).edit {
            putString("message", error.message ?: "无法读取同步配置，请在设置中重新保存")
        }
        Result.failure()
    } catch (error: Exception) {
        applicationContext.getSharedPreferences("phone-sync-status", Context.MODE_PRIVATE).edit {
            putString("message", error.message?.take(160) ?: "同步失败，下次自动重试")
        }
        if (runAttemptCount < 4) Result.retry() else Result.failure()
    }
}

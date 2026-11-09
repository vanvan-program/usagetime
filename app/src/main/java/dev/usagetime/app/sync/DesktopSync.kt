package dev.usagetime.app.sync

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

data class DesktopAppUse(
    val app: String,
    val label: String,
    val category: String,
    val categoryLabel: String,
    val color: String,
    val millis: Long,
    val sessions: Long
)

data class DesktopCategoryUse(
    val category: String,
    val label: String,
    val color: String,
    val millis: Long
)

data class DesktopDay(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val zone: String,
    val date: String,
    val recordedMillis: Long,
    val firstMillis: Long,
    val lastMillis: Long,
    val updatedAt: String,
    val apps: List<DesktopAppUse>,
    val categories: List<DesktopCategoryUse>
)

data class DesktopDevice(
    val id: String,
    val name: String,
    val platform: String,
    val zone: String,
    val updatedAt: String,
    val days: Map<String, String>
)

data class DesktopIndex(val devices: List<DesktopDevice> = emptyList()) {
    fun device(id: String): DesktopDevice? = devices.firstOrNull { it.id == id }
    fun latest(): DesktopDevice? = devices.maxByOrNull { it.updatedAt }
}

/**
 * 电脑端 Hindsight 发布到 WebDAV 的每日应用汇总。只缓存聚合结果，
 * 不接触电脑原始活动、窗口标题或截图。
 */
object DesktopSync {
    const val NAMESPACE = "hindsight-desktop-v1"
    private const val AAD = "hindsight-desktop-sync-v1"
    private const val FORMAT_INDEX = "usagetime.desktop.index"
    private const val FORMAT_MANIFEST = "hindsight.desktop.manifest"
    private const val FORMAT_DAY = "hindsight.desktop.day"
    private const val MAX_DAYS = 40_000
    private const val MAX_DEVICES = 100
    private const val MAX_APPS = 5_000
    private const val MAX_DAY_MILLIS = 26L * 3_600_000
    private val mutex = Mutex()

    fun root(text: String): URI {
        val base = URI(text.trim().trimEnd('/') + "/")
        require(
            base.scheme == "https" && !base.host.isNullOrBlank() &&
                base.userInfo == null && base.query == null && base.fragment == null
        ) { "请输入 HTTPS WebDAV 地址，账号密码请填在独立输入框" }
        return base.resolve("$NAMESPACE/")
    }

    private fun directory(context: Context) = File(context.filesDir, "desktop-sync")
    private fun indexFile(context: Context) = AtomicFile(File(directory(context), "index.json"))

    fun readIndex(context: Context): DesktopIndex {
        val file = indexFile(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DesktopIndex()
        val text = runCatching { String(file.readFully(), Charsets.UTF_8) }.getOrNull()
            ?: return DesktopIndex()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return DesktopIndex()
        if (json.optString("format") != FORMAT_INDEX || json.optInt("version") != 1) return DesktopIndex()
        val array = json.optJSONArray("devices") ?: return DesktopIndex()
        val devices = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (!validDevice(id)) continue
                val days = item.optJSONObject("days") ?: JSONObject()
                val map = buildMap {
                    days.keys().forEach { date ->
                        val hash = days.optString(date)
                        if (validDate(date) && hash.matches(HASH)) put(date, hash)
                    }
                }
                add(
                    DesktopDevice(
                        id = id,
                        name = item.optString("name").ifBlank { "电脑" }.take(400),
                        platform = item.optString("platform").take(40),
                        zone = item.optString("zone").take(80),
                        updatedAt = item.optString("updatedAt"),
                        days = map
                    )
                )
            }
        }.sortedBy { it.name }
        return DesktopIndex(devices.take(MAX_DEVICES))
    }

    fun readDay(context: Context, deviceId: String, date: String): DesktopDay? {
        if (!validDevice(deviceId) || !validDate(date)) return null
        val device = readIndex(context).device(deviceId) ?: return null
        val hash = device.days[date] ?: return null
        val file = dayFile(context, deviceId, date, hash)
        if (!file.exists()) return null
        return runCatching { parseDay(file.readBytes()) }.getOrNull()
    }

    fun lastSuccess(context: Context): Long =
        context.getSharedPreferences("desktop-sync-status", Context.MODE_PRIVATE).getLong("lastSuccess", 0)

    fun status(context: Context): String =
        context.getSharedPreferences("desktop-sync-status", Context.MODE_PRIVATE).getString("message", "尚未拉取") ?: "尚未拉取"

    fun error(context: Context): String? =
        context.getSharedPreferences("desktop-sync-status", Context.MODE_PRIVATE).getString("error", null)

    suspend fun pullIfStale(context: Context, maxAgeMillis: Long) {
        if (!DesktopDataSettings.enabled(context)) return
        val age = System.currentTimeMillis() - lastSuccess(context)
        if (age in 0 until maxAgeMillis) return
        try { pull(context) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { }
    }

    suspend fun pull(context: Context): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!DesktopDataSettings.enabled(context)) return@withContext 0
            val config = PhoneSyncSettings(context).load()
            require(config.enabled) { "请先在「设置 → 云端同步」启用同步" }
            val status = context.getSharedPreferences("desktop-sync-status", Context.MODE_PRIVATE)
            status.edit().putString("message", "正在拉取电脑用时…").putString("error", null).apply()
            try {
                val pullContext = currentCoroutineContext()
                val count = pullLocked(context, config) { pullContext.ensureActive() }
                pullContext.ensureActive()
                status.edit()
                    .putLong("lastSuccess", System.currentTimeMillis())
                    .putString("message", if (count == 0) "已是最新，电脑用时没有新记录" else "已拉取 $count 个电脑日记录")
                    .putString("error", null)
                    .apply()
                count
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status.edit()
                    .putString("message", "电脑用时拉取失败，已保留上次缓存：${error.message ?: "网络错误"}")
                    .putString("error", error.message?.take(200) ?: "网络错误")
                    .apply()
                throw error
            }
        }
    }

    private fun pullLocked(context: Context, config: PhoneSyncConfig, ensureActive: () -> Unit): Int {
        fun ensureEnabled() {
            ensureActive()
            if (!DesktopDataSettings.enabled(context)) throw CancellationException("电脑数据显示已关闭")
        }
        ensureEnabled()
        val root = root(config.url)
        val secret = PhoneCrypto.key(config.pairingKey)
        val dav = PhoneDav(root, config.user, config.password, ::ensureEnabled)
        val listing = try { dav.propfind("") }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw IllegalStateException("无法连接 WebDAV，请检查地址和网络", error) }
        val hrefs = parseHrefs(listing)
        if (hrefs.isEmpty()) throw IllegalStateException("云端还没有电脑数据，请先在 Hindsight 修改版里发布一次")
        val previous = readIndex(context)
        val devices = mutableListOf<DesktopDevice>()
        var downloaded = 0
        for (href in hrefs) {
            ensureEnabled()
            val id = href.removeSuffix(".dhmanifest").takeIf { href.endsWith(".dhmanifest") } ?: continue
            if (!validDevice(id)) continue
            require(devices.size < MAX_DEVICES) { "电脑设备超过 100 台，停止拉取" }
            val path = "$id.dhmanifest"
            val clear = PhoneCrypto.decrypt(secret, path, dav.get(path, 4 * 1024 * 1024), AAD)
            val manifest = parseManifest(clear)
            require(manifest.id == id) { "电脑清单设备不匹配" }
            val dates = mutableMapOf<String, String>()
            for ((date, hash) in manifest.days) {
                ensureEnabled()
                val file = dayFile(context, id, date, hash)
                val known = previous.device(id)?.days?.get(date) == hash
                if (!file.exists() || !known) {
                    val dayPath = "$id/$date.$hash.dhday"
                    val body = PhoneCrypto.decrypt(secret, dayPath, dav.get(dayPath, 8 * 1024 * 1024), AAD)
                    ensureEnabled()
                    require(sha256(body) == hash) { "电脑日记录摘要不匹配" }
                    val day = parseDay(body)
                    require(day.deviceId == id && day.date == date) { "电脑日记录来源不匹配" }
                    writeDay(file, body)
                    downloaded++
                }
                dates[date] = hash
            }
            devices += DesktopDevice(
                id = id,
                name = manifest.name,
                platform = manifest.platform,
                zone = manifest.zone,
                updatedAt = manifest.updatedAt,
                days = dates
            )
        }
        if (devices.isEmpty()) throw IllegalStateException("云端没有可识别的电脑清单")
        ensureEnabled()
        writeIndex(context, DesktopIndex(devices))
        prune(context, devices)
        return downloaded
    }

    private fun writeIndex(context: Context, index: DesktopIndex) {
        val devices = JSONArray()
        index.devices.forEach { device ->
            val days = JSONObject()
            device.days.toSortedMap().forEach { (date, hash) -> days.put(date, hash) }
            devices.put(
                JSONObject()
                    .put("id", device.id)
                    .put("name", device.name)
                    .put("platform", device.platform)
                    .put("zone", device.zone)
                    .put("updatedAt", device.updatedAt)
                    .put("days", days)
            )
        }
        val body = JSONObject()
            .put("format", FORMAT_INDEX)
            .put("version", 1)
            .put("devices", devices)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val file = indexFile(context)
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(body)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun prune(context: Context, devices: List<DesktopDevice>) {
        val keep = devices.associateBy { it.id }
        val root = directory(context)
        for (candidate in root.listFiles().orEmpty()) {
            if (!candidate.isDirectory) continue
            val device = keep[candidate.name] ?: continue
            for (file in candidate.listFiles().orEmpty()) {
                if (!file.name.endsWith(".dhday")) continue
                val date = file.name.substringBefore('.')
                val hash = file.name.removeSuffix(".dhday").substringAfter('.')
                if (device.days[date] != hash) file.delete()
            }
        }
        val current = keep.keys
        for (candidate in root.listFiles().orEmpty()) {
            if (candidate.isDirectory && candidate.name !in current) candidate.deleteRecursively()
        }
    }

    private fun dayFile(context: Context, deviceId: String, date: String, hash: String): File =
        File(File(directory(context), deviceId), "$date.$hash.dhday")

    private fun writeDay(file: File, body: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".tmp-${file.name}")
        temporary.writeBytes(body)
        if (!temporary.renameTo(file)) {
            file.writeBytes(body)
            temporary.delete()
        }
    }

    internal fun parseHrefs(xml: ByteArray): List<String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.isExpandEntityReferences = false }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val nodes = document.getElementsByTagNameNS("DAV:", "href")
        val result = mutableListOf<String>()
        for (index in 0 until nodes.length) {
            val entry = nodes.item(index) as? Element ?: continue
            val href = entry.textContent?.trim().orEmpty()
            if (href.isEmpty()) continue
            val name = runCatching { URI(href).path }.getOrNull()
                ?.substringAfterLast('/')
                ?: href.substringAfterLast('/')
            if (name.endsWith(".dhmanifest")) result += name
        }
        return result.distinct()
    }

    private data class Manifest(
        val id: String,
        val name: String,
        val platform: String,
        val zone: String,
        val updatedAt: String,
        val days: Map<String, String>
    )

    private fun parseManifest(bytes: ByteArray): Manifest {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.optString("format") == FORMAT_MANIFEST && json.optInt("version") == 1) {
            "不支持的电脑同步格式，请升级两端应用"
        }
        val id = json.optString("deviceId")
        require(validDevice(id)) { "电脑设备 ID 无效" }
        val daysJson = json.optJSONObject("days") ?: JSONObject()
        require(daysJson.length() <= MAX_DAYS) { "电脑清单天数超过上限" }
        val days = buildMap {
            daysJson.keys().forEach { date ->
                val hash = daysJson.optString(date)
                require(validDate(date) && hash.matches(HASH)) { "电脑清单日期或摘要无效" }
                put(date, hash)
            }
        }
        return Manifest(
            id = id,
            name = json.optString("deviceName").ifBlank { "电脑" }.take(400),
            platform = json.optString("platform").take(40),
            zone = json.optString("zone").take(80),
            updatedAt = json.optString("updatedAt"),
            days = days
        )
    }

    fun parseDay(bytes: ByteArray): DesktopDay {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.optString("format") == FORMAT_DAY && json.optInt("version") == 1) {
            "不支持的电脑日记录格式"
        }
        val device = json.optString("deviceId")
        val date = json.optString("date")
        require(validDevice(device) && validDate(date)) { "电脑日记录日期或设备无效" }
        val appsJson = json.optJSONArray("apps") ?: JSONArray()
        require(appsJson.length() <= MAX_APPS) { "电脑应用数量超过上限" }
        val apps = buildList {
            for (index in 0 until appsJson.length()) {
                val item = appsJson.optJSONObject(index) ?: continue
                val app = item.optString("app")
                require(app.isNotBlank() && app.length <= 1000) { "电脑应用名称无效" }
                val millis = item.optLong("millis", -1)
                require(millis in 0..MAX_DAY_MILLIS) { "电脑应用用时无效" }
                add(
                    DesktopAppUse(
                        app = app,
                        label = item.optString("label").ifBlank { app }.take(2000),
                        category = item.optString("category").ifBlank { "other" }.take(400),
                        categoryLabel = item.optString("categoryLabel").ifBlank { "其他" }.take(400),
                        color = item.optString("color").take(40),
                        millis = millis,
                        sessions = item.optLong("sessions", 0).coerceAtLeast(0)
                    )
                )
            }
        }.sortedByDescending { it.millis }
        val categoriesJson = json.optJSONArray("categories") ?: JSONArray()
        require(categoriesJson.length() <= MAX_APPS) { "电脑分类数量超过上限" }
        val categories = buildList {
            for (index in 0 until categoriesJson.length()) {
                val item = categoriesJson.optJSONObject(index) ?: continue
                val id = item.optString("category")
                val millis = item.optLong("millis", -1)
                require(id.isNotBlank() && millis in 0..MAX_DAY_MILLIS) { "电脑分类用时无效" }
                add(
                    DesktopCategoryUse(
                        category = id.take(400),
                        label = item.optString("label").ifBlank { id }.take(400),
                        color = item.optString("color").take(40),
                        millis = millis
                    )
                )
            }
        }.sortedByDescending { it.millis }
        val recorded = json.optLong("recordedMillis", -1)
        require(recorded in 0..MAX_DAY_MILLIS) { "电脑累计用时无效" }
        require(apps.sumOf { it.millis } == recorded) { "电脑应用累计与总计不一致" }
        require(categories.sumOf { it.millis } == recorded) { "电脑分类累计与总计不一致" }
        return DesktopDay(
            deviceId = device,
            deviceName = json.optString("deviceName").take(400),
            platform = json.optString("platform").take(40),
            zone = json.optString("zone").take(80),
            date = date,
            recordedMillis = recorded,
            firstMillis = json.optLong("firstMillis", 0).coerceAtLeast(0),
            lastMillis = json.optLong("lastMillis", 0).coerceAtLeast(0),
            updatedAt = json.optString("updatedAt"),
            apps = apps,
            categories = categories
        )
    }

    private val HASH = Regex("[0-9a-f]{64}")

    fun validDevice(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun validDate(date: String): Boolean =
        runCatching { java.time.LocalDate.parse(date).toString() == date }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

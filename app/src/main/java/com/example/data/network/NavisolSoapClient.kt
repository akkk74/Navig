package com.example.data.network

import com.example.data.model.GeoZone
import com.example.data.model.SensorItem
import com.example.data.model.TrackPoint
import com.example.data.model.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class SoapContext(
    val login: String,
    val password: String,
    var token: String = "",
    var groupId: String = "",
    val objectId: String = "",
    val fromDate: String = "",
    val toDate: String = ""
)

data class CachedSoapSession(
    val serverUrl: String,
    val namespace: String,
    val wsdl: WsdlInfo,
    val ctx: SoapContext,
    val methodName: String,
    val paramsObj: Map<String, String>
)

data class ConnectResult(
    val ok: Boolean,
    val vehicles: List<Vehicle> = emptyList(),
    val error: String? = null,
    val logs: List<String> = emptyList()
)

data class RefreshResult(
    val ok: Boolean,
    val vehicles: List<Vehicle> = emptyList(),
    val error: String? = null,
    val netMs: Long = 0L,
    val parseMs: Long = 0L,
    val bytes: Int = 0
)

data class HistoryResult(
    val ok: Boolean,
    val points: List<TrackPoint> = emptyList(),
    val method: String = "",
    val format: String = "",
    val error: String? = null,
    val debug: String = ""
)

data class ZonesResult(
    val ok: Boolean,
    val zones: List<GeoZone> = emptyList(),
    val error: String? = null,
    val debug: String = ""
)

class NavisolSoapClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val fastRefreshClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var sessionCookie: String = ""

    @Volatile
    private var preferredHistoryFormatLabel: String? = null

    @Volatile
    var cachedSession: CachedSoapSession? = null
        private set

    private data class HttpResponse(val status: Int, val text: String)

    private fun nativeHttp(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        httpClient: OkHttpClient = client
    ): HttpResponse {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (sessionCookie.isNotEmpty()) {
            builder.header("Cookie", sessionCookie)
        }
        if (method == "POST") {
            val mediaType = (headers["Content-Type"] ?: "text/xml; charset=utf-8").toMediaType()
            builder.post((body ?: "").toRequestBody(mediaType))
        } else {
            builder.get()
        }

        httpClient.newCall(builder.build()).execute().use { resp ->
            val setCookies = resp.headers("Set-Cookie")
            if (setCookies.isNotEmpty()) {
                val cookiePair = setCookies.first().substringBefore(";").trim()
                if (cookiePair.isNotEmpty()) {
                    sessionCookie = cookiePair
                }
            }
            val text = resp.body?.string().orEmpty()
            return HttpResponse(resp.code, text)
        }
    }

    private fun callSoap(
        serverUrl: String,
        namespace: String,
        methodName: String,
        paramsObj: Map<String, String>,
        httpClient: OkHttpClient = client
    ): HttpResponse {
        val nsClean = if (namespace.endsWith("/")) namespace else "$namespace/"
        val paramsXml = paramsObj.entries.joinToString("") { (k, v) ->
            "<$k>${XmlParser.escapeXml(v)}</$k>"
        }
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xmlns:xsd="http://www.w3.org/2001/XMLSchema"
               xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <$methodName xmlns="$namespace">
      $paramsXml
    </$methodName>
  </soap:Body>
</soap:Envelope>"""

        return nativeHttp(
            url = serverUrl,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "text/xml; charset=utf-8",
                "SOAPAction" to "\"$nsClean$methodName\""
            ),
            body = envelope,
            httpClient = httpClient
        )
    }

    private fun buildParamsForMethod(paramDefs: List<WsdlParam>, ctx: SoapContext): MutableMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (p in paramDefs) {
            val n = p.name.lowercase(Locale.ROOT)
            val t = p.type.lowercase(Locale.ROOT)
            result[p.name] = when {
                Regex("""psw|pass|pwd""").containsMatchIn(n) -> ctx.password
                Regex("""login|user|uname""").containsMatchIn(n) -> ctx.login
                Regex("""token|session|sid|guid|key""").containsMatchIn(n) -> ctx.token
                Regex("""group""").containsMatchIn(n) -> {
                    if (Regex("""int|long|short|byte|dec|num""").containsMatchIn(t)) {
                        if (ctx.groupId.matches(Regex("""^-?\d+$"""))) ctx.groupId else "0"
                    } else {
                        ctx.groupId
                    }
                }
                Regex("""obj|unit|car|veh|device|ts|tc|^id$|^pid$|objectid|imei""").containsMatchIn(n) && ctx.objectId.isNotEmpty() -> ctx.objectId
                Regex("""from|start|begin|beg|dt1|date1|d1""").containsMatchIn(n) && ctx.fromDate.isNotEmpty() -> ctx.fromDate
                Regex("""to|end|finish|dt2|date2|d2""").containsMatchIn(n) && ctx.toDate.isNotEmpty() -> ctx.toDate
                Regex("""date|time|dt""").containsMatchIn(n) && ctx.fromDate.isNotEmpty() -> ctx.fromDate
                Regex("""bool""").containsMatchIn(t) -> "false"
                Regex("""int|long|short|byte|decimal|double|float""").containsMatchIn(t) -> "0"
                else -> ""
            }
        }
        return result
    }

    private fun nowTime(): String = SimpleDateFormat("HH:mm:ss", Locale("ru", "RU")).format(Date())

    suspend fun performConnect(serverUrl: String, login: String, password: String): ConnectResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            logs.add("[${nowTime()}] $msg")
        }

        sessionCookie = ""
        preferredHistoryFormatLabel = null
        cachedSession = null

        val cleanUrl = serverUrl.trim()
        if (cleanUrl.isEmpty()) {
            return@withContext ConnectResult(ok = false, error = "Укажите URL сервера (.asmx)", logs = listOf("[${nowTime()}] Пустой адрес сервера"))
        }

        try {
            log("Подключение к $cleanUrl")
            val wsdlRes = nativeHttp(
                url = "$cleanUrl?WSDL",
                method = "GET",
                headers = mapOf("Accept" to "text/xml, application/xml, */*")
            )
            if (wsdlRes.status >= 400 || wsdlRes.text.isBlank()) {
                throw IllegalStateException("WSDL недоступен (HTTP ${wsdlRes.status})")
            }

            val wsdl = XmlParser.parseWsdl(wsdlRes.text)
            log("Namespace: ${wsdl.namespace}")
            log("Методов в WSDL (${wsdl.methods.size}): ${wsdl.methods.joinToString("; ") { m -> "${m.name}(${m.params.joinToString(",") { it.name }})" }}")

            val ctx = SoapContext(login = login, password = password)

            val groupsMethod = wsdl.methods.firstOrNull { Regex("""getusergroups""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            if (groupsMethod != null) {
                val gParams = buildParamsForMethod(groupsMethod.params, ctx)
                log("Запрос групп: ${groupsMethod.name}(${gParams.keys.joinToString(", ")})")
                runCatching {
                    val gRes = callSoap(cleanUrl, wsdl.namespace, groupsMethod.name, gParams)
                    val gClean = XmlParser.unescapeXml(gRes.text)
                    log("Ответ ${groupsMethod.name} [HTTP ${gRes.status}]: ${gClean.take(300)}")
                    val gidMatch = Regex("""<(?:[a-z0-9]+:)?(?:Id|ID|GroupId|GroupID|Code)>([^<]+)</""", RegexOption.IGNORE_CASE).find(gClean)
                    if (gidMatch != null) {
                        ctx.groupId = gidMatch.groupValues[1].trim()
                        log("Найдена группа ID: ${ctx.groupId}")
                    }
                }.onFailure { e ->
                    log("Ошибка ${groupsMethod.name}: ${e.message}")
                }
            }

            val loginMethods = wsdl.methods.filter { Regex("""login|auth|logon|signin|connect|checkuser""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            for (lm in loginMethods) {
                val params = buildParamsForMethod(lm.params, ctx)
                log("Вызов авторизации: ${lm.name}(${params.keys.joinToString(", ")})")
                val res = callSoap(cleanUrl, wsdl.namespace, lm.name, params)
                log("Ответ ${lm.name} [HTTP ${res.status}]: ${res.text.take(260)}")

                val guidMatch = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""").find(res.text)
                if (guidMatch != null) {
                    ctx.token = guidMatch.value
                    log("Получен токен сессии: ${ctx.token}")
                } else {
                    val resultTag = Regex("""<[A-Za-z0-9_]+Result>([^<]{4,128})</[A-Za-z0-9_]+Result>""", RegexOption.IGNORE_CASE).find(res.text)
                    if (resultTag != null && !Regex("""true|false|error|fail""", RegexOption.IGNORE_CASE).containsMatchIn(resultTag.groupValues[1])) {
                        ctx.token = resultTag.groupValues[1].trim()
                        log("Получен ключ ответа: ${ctx.token}")
                    }
                }

                val directVehicles = XmlParser.extractVehicles(res.text)
                if (directVehicles.isNotEmpty()) {
                    cachedSession = CachedSoapSession(cleanUrl, wsdl.namespace, wsdl, ctx.copy(), lm.name, params)
                    log("Загружено объектов напрямую: ${directVehicles.size}")
                    return@withContext ConnectResult(ok = true, vehicles = directVehicles, logs = logs)
                }
            }

            val dataMethods = wsdl.methods
                .filter { m ->
                    Regex("""object|unit|vehicle|car|state|pos|coord|last|data|list|monitor|info|current|device|ts|tc""", RegexOption.IGNORE_CASE).containsMatchIn(m.name) &&
                        !Regex("""history|track|route|report|sensor|zone|photo|image|version|ping|logout|group""", RegexOption.IGNORE_CASE).containsMatchIn(m.name)
                }
                .sortedByDescending { if (Regex("""getobjectslistmobile""", RegexOption.IGNORE_CASE).containsMatchIn(it.name)) 1 else 0 }

            val candidates = if (dataMethods.isNotEmpty()) {
                dataMethods
            } else {
                wsdl.methods.filter { it !in loginMethods && it != groupsMethod && it.params.size <= 5 }
            }

            for (dm in candidates) {
                val hasGroupParam = dm.params.any { Regex("""group""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                val groupVariants = if (hasGroupParam) {
                    linkedSetOf(ctx.groupId, "0", "-1", "").toList()
                } else {
                    listOf(ctx.groupId)
                }

                val dtParam = dm.params.firstOrNull { Regex("""fromdt|fromdate|date|dt""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                val dtVariants: List<String?> = if (dtParam != null) {
                    listOf(null, "01.01.2000 00:00:00", "2000-01-01T00:00:00", "2000-01-01 00:00:00", "0", "")
                } else {
                    listOf(null)
                }

                for (gv in groupVariants) {
                    for (dtVal in dtVariants) {
                        val tryCtx = ctx.copy(groupId = gv)
                        val params = buildParamsForMethod(dm.params, tryCtx)
                        if (dtParam != null) {
                            if (dtVal == null) params.remove(dtParam.name) else params[dtParam.name] = dtVal
                        }
                        val safeLogParams = params.entries.joinToString(", ") { (k, v) ->
                            val masked = if (Regex("""psw|pass|pwd""", RegexOption.IGNORE_CASE).containsMatchIn(k)) "***" else v
                            "$k=$masked"
                        }
                        log("Запрос данных: ${dm.name}($safeLogParams)")
                        runCatching {
                            val res = callSoap(cleanUrl, wsdl.namespace, dm.name, params)
                            val cleanBody = XmlParser.unescapeXml(res.text)
                            log("Ответ ${dm.name} [HTTP ${res.status}, ${res.text.length} байт]: ${cleanBody.take(400)}")
                            val found = XmlParser.extractVehicles(res.text)
                            if (found.isNotEmpty()) {
                                cachedSession = CachedSoapSession(cleanUrl, wsdl.namespace, wsdl, tryCtx, dm.name, params)
                                log("Успешно распознано объектов: ${found.size}")
                                return@withContext ConnectResult(ok = true, vehicles = found, logs = logs)
                            }
                        }.onFailure { e ->
                            log("Ошибка метода ${dm.name}: ${e.message}")
                        }
                    }
                }
            }

            ConnectResult(
                ok = false,
                error = "Сервер ответил, но координаты объектов не распознаны автоматически. Проверьте логин/пароль или скопируйте журнал.",
                logs = logs
            )
        } catch (err: Exception) {
            log("ОШИБКА: ${err.message ?: err.javaClass.simpleName}")
            ConnectResult(ok = false, error = err.message ?: "Ошибка сети", logs = logs)
        }
    }

    suspend fun performFastRefresh(): RefreshResult = withContext(Dispatchers.IO) {
        val session = cachedSession ?: return@withContext RefreshResult(ok = false, error = "Нет активной сессии")
        try {
            val t0 = System.currentTimeMillis()
            val res = callSoap(
                serverUrl = session.serverUrl,
                namespace = session.namespace,
                methodName = session.methodName,
                paramsObj = session.paramsObj,
                httpClient = fastRefreshClient
            )
            val t1 = System.currentTimeMillis()
            val found = XmlParser.extractVehicles(res.text)
            val t2 = System.currentTimeMillis()
            if (found.isNotEmpty()) {
                RefreshResult(
                    ok = true,
                    vehicles = found,
                    netMs = t1 - t0,
                    parseMs = t2 - t1,
                    bytes = res.text.length
                )
            } else {
                RefreshResult(ok = false, error = "Пустой ответ при обновлении (HTTP ${res.status})")
            }
        } catch (err: Exception) {
            RefreshResult(ok = false, error = err.message ?: "Ошибка обновления")
        }
    }

    private data class DateVariants(
        val ruLocal: String,
        val isoLocal: String,
        val spaceLocal: String,
        val ruUtc: String,
        val isoUtc: String,
        val spaceUtc: String,
        val unixSec: String
    )

    private fun formatDateVariants(ms: Long): DateVariants {
        val d = Date(ms)
        fun fmt(pattern: String, utc: Boolean = false): String {
            val sdf = SimpleDateFormat(pattern, Locale.US)
            if (utc) sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(d)
        }
        return DateVariants(
            ruLocal = fmt("dd.MM.yyyy HH:mm:ss", false),
            isoLocal = fmt("yyyy-MM-dd'T'HH:mm:ss", false),
            spaceLocal = fmt("yyyy-MM-dd HH:mm:ss", false),
            ruUtc = fmt("dd.MM.yyyy HH:mm:ss", true),
            isoUtc = fmt("yyyy-MM-dd'T'HH:mm:ss", true),
            spaceUtc = fmt("yyyy-MM-dd HH:mm:ss", true),
            unixSec = (ms / 1000L).toString()
        )
    }

    suspend fun performGetHistory(
        objectId: String,
        fromMs: Long,
        toMs: Long,
        altIds: List<String> = emptyList()
    ): HistoryResult = withContext(Dispatchers.IO) {
        val session = cachedSession ?: return@withContext HistoryResult(ok = false, error = "Сначала подключитесь к серверу")
        val histMethod = session.wsdl.methods.firstOrNull { Regex("""history|track|route""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            ?: return@withContext HistoryResult(ok = false, error = "Метод истории маршрута не найден в WSDL")

        val f = formatDateVariants(fromMs)
        val t = formatDateVariants(toMs)
        val baseAttempts = listOf(
            Triple("RU-Local", f.ruLocal, t.ruLocal),
            Triple("RU-UTC", f.ruUtc, t.ruUtc),
            Triple("ISO-Local", f.isoLocal, t.isoLocal),
            Triple("ISO-UTC", f.isoUtc, t.isoUtc),
            Triple("Space-Local", f.spaceLocal, t.spaceLocal),
            Triple("Space-UTC", f.spaceUtc, t.spaceUtc),
            Triple("UnixSec", f.unixSec, t.unixSec)
        )
        // Prioritize the last known working format so history queries finish in 1 round-trip
        val cachedFmt = preferredHistoryFormatLabel
        val attempts = if (cachedFmt != null) {
            val primary = baseAttempts.filter { it.first == cachedFmt }
            val fallback = baseAttempts.filter { it.first != cachedFmt }.take(2)
            primary + fallback
        } else {
            baseAttempts
        }

        val idCandidates = linkedSetOf(objectId).apply {
            altIds.filter { it.isNotBlank() && it != objectId }.take(3).forEach { add(it) }
        }.toList()

        val debugLines = mutableListOf<String>()
        val dateParams = histMethod.params.filter { p ->
            Regex("""date|time|from|to|start|end|begin|finish|dt""", RegexOption.IGNORE_CASE).containsMatchIn(p.name) ||
                Regex("""datetime""", RegexOption.IGNORE_CASE).containsMatchIn(p.type)
        }

        for (candId in idCandidates) {
            for ((label, fromStr, toStr) in attempts) {
                val histCtx = session.ctx.copy(objectId = candId, fromDate = fromStr, toDate = toStr)
                val params = buildParamsForMethod(histMethod.params, histCtx)
                if (dateParams.size >= 2) {
                    params[dateParams[0].name] = fromStr
                    params[dateParams[1].name] = toStr
                }
                try {
                    val res = callSoap(session.serverUrl, session.namespace, histMethod.name, params)
                    val points = XmlParser.extractTrackPoints(res.text)
                    if (points.isNotEmpty()) {
                        preferredHistoryFormatLabel = label
                        return@withContext HistoryResult(
                            ok = true,
                            points = points,
                            method = histMethod.name,
                            format = label
                        )
                    }
                    val safeParams = params.entries.joinToString(", ") { (k, v) ->
                        val mv = if (Regex("""psw|pass|pwd""", RegexOption.IGNORE_CASE).containsMatchIn(k)) "***" else v
                        "$k=$mv"
                    }
                    val snippet = XmlParser.unescapeXml(res.text).take(400)
                    debugLines.add("[$label] ${histMethod.name}($safeParams) -> HTTP ${res.status} (${res.text.length}б): $snippet")
                } catch (err: Exception) {
                    debugLines.add("[$label] Ошибка: ${err.message}")
                }
            }
        }

        HistoryResult(
            ok = false,
            error = "За выбранный период точек маршрута не найдено.",
            debug = debugLines.take(2).joinToString("\n")
        )
    }

    suspend fun performGetSensors(objectId: String): List<SensorItem> = withContext(Dispatchers.IO) {
        val session = cachedSession ?: return@withContext emptyList()
        val sensMethod = session.wsdl.methods.firstOrNull { Regex("""sensor""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            ?: return@withContext emptyList()
        val params = buildParamsForMethod(sensMethod.params, session.ctx.copy(objectId = objectId))
        runCatching {
            val res = callSoap(session.serverUrl, session.namespace, sensMethod.name, params)
            XmlParser.extractSensors(res.text)
        }.getOrDefault(emptyList())
    }

    suspend fun performGetZones(): ZonesResult = withContext(Dispatchers.IO) {
        val session = cachedSession ?: return@withContext ZonesResult(ok = false, error = "Сначала подключитесь к серверу")
        val zoneMethod = session.wsdl.methods.firstOrNull { Regex("""zone|geofence""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            ?: return@withContext ZonesResult(ok = false, error = "Метод геозон не найден в WSDL")
        val params = buildParamsForMethod(zoneMethod.params, session.ctx)
        try {
            val res = callSoap(session.serverUrl, session.namespace, zoneMethod.name, params)
            val zones = XmlParser.extractZones(res.text)
            ZonesResult(ok = true, zones = zones, debug = "${zoneMethod.name} -> HTTP ${res.status}, найдено зон: ${zones.size}")
        } catch (err: Exception) {
            ZonesResult(ok = false, error = "Ошибка загрузки геозон: ${err.message}")
        }
    }
}

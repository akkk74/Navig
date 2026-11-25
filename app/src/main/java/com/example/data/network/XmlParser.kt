package com.example.data.network

import com.example.data.model.GeoZone
import com.example.data.model.GeoZoneKind
import com.example.data.model.LatLngPoint
import com.example.data.model.SensorItem
import com.example.data.model.TrackPoint
import com.example.data.model.Vehicle
import com.example.domain.GeoAnalytics
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class WsdlParam(val name: String, val type: String)
data class WsdlMethod(val name: String, val params: List<WsdlParam>)
data class WsdlInfo(val namespace: String, val methods: List<WsdlMethod>)

data class XmlNode(
    val tag: String,
    val attrs: MutableMap<String, String> = LinkedHashMap(),
    var text: String = "",
    val children: MutableList<XmlNode> = mutableListOf()
)

object XmlParser {

    fun escapeXml(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun unescapeXml(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return str
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    fun parseCoord(value: String?): Double {
        if (value == null) return Double.NaN
        return value.trim().replace(",", ".").toDoubleOrNull() ?: Double.NaN
    }

    fun isValidLatLng(lat: Double, lng: Double): Boolean {
        return !lat.isNaN() && !lng.isNaN() &&
            lat in -90.0..90.0 && lng in -180.0..180.0 &&
            (lat != 0.0 || lng != 0.0)
    }

    fun parseWsdl(xml: String): WsdlInfo {
        val nsMatch = Regex("""targetNamespace\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xml)
        val namespace = nsMatch?.groupValues?.get(1) ?: "http://tempuri.org/"

        val opNames = LinkedHashSet<String>()
        val opRegex = Regex("""<(?:[a-z0-9]+:)?operation\b[^>]*\bname\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        opRegex.findAll(xml).forEach { opNames.add(it.groupValues[1]) }

        val methods = mutableListOf<WsdlMethod>()
        val tagScanner = Regex("""<(/?)(?:[a-z0-9]+:)?element\b([^>]*)>""", RegexOption.IGNORE_CASE)
        val paramRegex = Regex("""<(?:[a-z0-9]+:)?element\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
        val nameAttrRegex = Regex("""\bname\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val typeAttrRegex = Regex("""\btype\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        for (opName in opNames) {
            val params = mutableListOf<WsdlParam>()
            val safeOp = Regex.escape(opName)
            val startRegex = Regex("""<(?:[a-z0-9]+:)?element\b[^>]*\bname\s*=\s*["']$safeOp["'][^>]*>""", RegexOption.IGNORE_CASE)

            for (sm in startRegex.findAll(xml)) {
                if (sm.value.endsWith("/>")) continue
                val contentStart = sm.range.last + 1
                var depth = 1
                var contentEnd = -1
                var tm = tagScanner.find(xml, contentStart)
                while (tm != null) {
                    val isClosing = tm.groupValues[1] == "/"
                    val isSelfClosing = tm.groupValues[2].trim().endsWith("/")
                    if (isClosing) {
                        depth--
                        if (depth == 0) {
                            contentEnd = tm.range.first
                            break
                        }
                    } else if (!isSelfClosing) {
                        depth++
                    }
                    tm = tagScanner.find(xml, tm.range.last + 1)
                }

                if (contentEnd > contentStart) {
                    val inner = xml.substring(contentStart, contentEnd)
                    paramRegex.findAll(inner).forEach { pm ->
                        val attrs = pm.groupValues[1]
                        val n = nameAttrRegex.find(attrs)?.groupValues?.get(1)
                        val t = typeAttrRegex.find(attrs)?.groupValues?.get(1) ?: "string"
                        if (n != null && params.none { it.name == n }) {
                            params.add(WsdlParam(n, t))
                        }
                    }
                    if (params.isNotEmpty()) break
                }
            }

            if (params.isEmpty()) {
                val msgRegex = Regex(
                    """<(?:[a-z0-9]+:)?message\b[^>]*\bname\s*=\s*["']${safeOp}(?:SoapIn|HttpPostIn|HttpGetIn)["'][^>]*>([\s\S]*?)</(?:[a-z0-9]+:)?message>""",
                    RegexOption.IGNORE_CASE
                )
                val msgMatch = msgRegex.find(xml)
                if (msgMatch != null) {
                    val partRegex = Regex("""<(?:[a-z0-9]+:)?part\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
                    partRegex.findAll(msgMatch.groupValues[1]).forEach { ptm ->
                        val attrs = ptm.groupValues[1]
                        val n = nameAttrRegex.find(attrs)?.groupValues?.get(1)
                        val t = typeAttrRegex.find(attrs)?.groupValues?.get(1) ?: "string"
                        if (n != null && !n.equals("parameters", ignoreCase = true)) {
                            params.add(WsdlParam(n, t))
                        }
                    }
                }
            }

            methods.add(WsdlMethod(opName, params))
        }

        return WsdlInfo(namespace, methods)
    }

    fun parseXmlTree(xmlString: String, depth: Int = 0): XmlNode {
        val root = XmlNode(tag = "root")
        if (xmlString.isBlank() || depth > 3) return root

        val cleaned = xmlString
            .replace(Regex("""<\?[\s\S]*?\?>"""), "")
            .replace(Regex("""<!--[\s\S]*?-->"""), "")
            .replace(Regex("""<!\[CDATA\[([\s\S]*?)]]>""")) { m -> escapeXml(m.groupValues[1]) }

        val stack = ArrayList<XmlNode>()
        stack.add(root)

        val tagRegex = Regex("""<(/?)([A-Za-z0-9_:-]+)([^>]*?)(/?)>""")
        val attrRe = Regex("""([A-Za-z0-9_:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        var lastIndex = 0

        for (match in tagRegex.findAll(cleaned)) {
            if (match.range.first > lastIndex) {
                val txt = cleaned.substring(lastIndex, match.range.first)
                if (txt.isNotBlank()) {
                    stack.last().text += unescapeXml(txt)
                }
            }
            lastIndex = match.range.last + 1

            val isClose = match.groupValues[1] == "/"
            val fullTag = match.groupValues[2]
            val attrStr = match.groupValues[3]
            val isSelfClose = match.groupValues[4] == "/" || attrStr.trim().endsWith("/")
            val localTag = if (fullTag.contains(':')) fullTag.substringAfterLast(':') else fullTag

            if (isClose) {
                for (i in stack.size - 1 downTo 1) {
                    if (stack[i].tag == localTag) {
                        while (stack.size > i) stack.removeAt(stack.size - 1)
                        break
                    }
                }
            } else {
                val attrs = LinkedHashMap<String, String>()
                if (attrStr.contains('=')) {
                    attrRe.findAll(attrStr).forEach { am ->
                        val rawName = am.groupValues[1]
                        val aName = if (rawName.contains(':')) rawName.substringAfterLast(':') else rawName
                        val aVal = if (am.groups[2] != null) am.groupValues[2] else am.groupValues[3]
                        attrs[aName] = unescapeXml(aVal)
                    }
                }
                val node = XmlNode(tag = localTag, attrs = attrs)
                stack.last().children.add(node)
                if (!isSelfClose) {
                    stack.add(node)
                }
            }
        }

        fun walkAndExpand(node: XmlNode) {
            if (node.children.isEmpty() && node.text.isNotEmpty()) {
                val t = node.text.trim()
                if (t.startsWith("<") && t.endsWith(">") && t.length > 5) {
                    val sub = parseXmlTree(t, depth + 1)
                    if (sub.children.isNotEmpty()) {
                        node.children.addAll(sub.children)
                        node.text = ""
                    }
                }
            }
            for (ch in node.children) {
                walkAndExpand(ch)
            }
        }
        walkAndExpand(root)
        return root
    }

    private fun walkXmlNodes(node: XmlNode, visitor: (XmlNode) -> Unit) {
        visitor(node)
        for (ch in node.children) {
            walkXmlNodes(ch, visitor)
        }
    }

    private fun collectNodeFields(node: XmlNode): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        for ((k, v) in node.attrs) {
            if (v.isNotEmpty() && !k.equals("xmlns", ignoreCase = true)) {
                fields[k] = v.trim()
            }
        }
        for (ch in node.children) {
            if (ch.children.isEmpty() && ch.text.trim().isNotEmpty()) {
                fields[ch.tag] = ch.text.trim()
            } else if (ch.children.isNotEmpty() && ch.children.size <= 10) {
                val hasOnlyLeaves = ch.children.all { it.children.isEmpty() }
                if (hasOnlyLeaves) {
                    for (sub in ch.children) {
                        val trimmed = sub.text.trim()
                        if (trimmed.isNotEmpty() && !fields.containsKey(sub.tag)) {
                            fields[sub.tag] = trimmed
                        }
                    }
                }
            }
        }
        return fields
    }

    private fun findByRegex(fields: Map<String, String>, regex: Regex): String {
        val key = fields.keys.firstOrNull { regex.containsMatchIn(it) }
        return if (key != null) fields[key].orEmpty() else ""
    }

    private val exactLatRe = Regex("""^(lat|latitude|y|lt|la|coordy|poslat|lastlat|gpslat|plat|shirota)$""", RegexOption.IGNORE_CASE)
    private val looseLatRe = Regex("""(?:^|_)lat(?:itude)?(?:$|_|\d)|lat(?:itude)?$""", RegexOption.IGNORE_CASE)
    private val badLatRe = Regex("""plate|relat|late|isolat|translat|accumulat|calculat|interpolat|ventilat|simulat|flat""", RegexOption.IGNORE_CASE)
    private val exactLngRe = Regex("""^(lon|lng|long|longitude|x|ln|lo|coordx|poslon|lastlon|gpslon|plon|dolgota)$""", RegexOption.IGNORE_CASE)
    private val looseLngRe = Regex("""(?:^|_)(?:lon|lng|long(?:itude)?)(?:$|_|\d)|(?:lon|lng|longitude)$""", RegexOption.IGNORE_CASE)
    private val badLngRe = Regex("""belong|along|clone|salon|colon|prolong|oblong""", RegexOption.IGNORE_CASE)

    private fun findLatLng(fields: Map<String, String>): Pair<String, String> {
        var latStr = findByRegex(fields, exactLatRe)
        if (latStr.isEmpty()) {
            val k = fields.keys.firstOrNull { looseLatRe.containsMatchIn(it) && !badLatRe.containsMatchIn(it) }
            if (k != null) latStr = fields[k].orEmpty()
        }
        var lngStr = findByRegex(fields, exactLngRe)
        if (lngStr.isEmpty()) {
            val k = fields.keys.firstOrNull { looseLngRe.containsMatchIn(it) && !badLngRe.containsMatchIn(it) }
            if (k != null) lngStr = fields[k].orEmpty()
        }
        return latStr to lngStr
    }

    private fun extractJsonCandidates(rawText: String): List<Map<String, String>> {
        val parsedRoots = mutableListOf<Any>()
        fun tryParse(str: String) {
            val t = str.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                runCatching { parsedRoots.add(JSONArray(t)) }
            } else if (t.startsWith("{") && t.endsWith("}")) {
                runCatching { parsedRoots.add(JSONObject(t)) }
            }
        }

        val resTagRe = Regex("""<[A-Za-z0-9_:-]*Result[^>]*>([\s\S]*?)</[A-Za-z0-9_:-]*Result>""", RegexOption.IGNORE_CASE)
        resTagRe.findAll(rawText).forEach { m ->
            var inner = unescapeXml(m.groupValues[1]).trim()
            if (inner.contains("&lt;") || inner.contains("&quot;")) {
                inner = unescapeXml(inner).trim()
            }
            tryParse(inner)
        }
        if (parsedRoots.isEmpty()) {
            tryParse(unescapeXml(rawText))
        }

        val flatItems = mutableListOf<Map<String, String>>()
        fun walk(value: Any?) {
            when (value) {
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i))
                }
                is JSONObject -> {
                    val flat = LinkedHashMap<String, String>()
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = value.opt(k)
                        if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                            flat[k] = v.toString().trim()
                        } else if (v is JSONObject || v is JSONArray) {
                            walk(v)
                        }
                    }
                    if (flat.size >= 2) flatItems.add(flat)
                }
            }
        }
        parsedRoots.forEach { walk(it) }
        return flatItems
    }

    fun parseEngineOffFromFields(rawFields: Map<String, String>, speed: Double, duration: String): Boolean? {
        if (speed > 2.0) return false
        if (Regex("""заглуш|выкл|масс""", RegexOption.IGNORE_CASE).containsMatchIn(duration)) return true
        for ((k, v) in rawFields) {
            val kl = k.lowercase(Locale.ROOT)
            val sv = v.trim().lowercase(Locale.ROOT)
            if (Regex("""^(ign|ignition|isign|isignition|engine|isengine|engineon|ignitionon|motor|zazh|zazhiganie|зажигание|двигатель|двс|mass|massa|масса|power|pwr|питание)$""").matches(kl) ||
                Regex("""(?:^|_)(?:ign|ignition|engine|motor|зажиг|двиг|масс|питан)""").containsMatchIn(kl)
            ) {
                if (Regex("""^(0|false|no|off|выкл|нет|заглуш)""").containsMatchIn(sv)) return true
                if (Regex("""^(1|true|yes|on|вкл|да|завед|работ)""").containsMatchIn(sv)) return false
            }
            if (Regex("""^(volt|voltage|vbat|pwrvolt|akb|акб|напряжение|бортсеть)$""").matches(kl) ||
                Regex("""(?:volt|напряж|борт)""").containsMatchIn(kl)
            ) {
                val num = sv.replace(",", ".").toDoubleOrNull()
                if (num != null && num <= 10.0) return true
            }
        }
        return null
    }

    fun findWeightInFields(fields: Map<String, String>): Double? {
        val k = fields.keys.firstOrNull { key ->
            Regex("""^(weight|w|load|ves|massa|cargo|tonn|payload|вес|груз|масса)$""", RegexOption.IGNORE_CASE).matches(key) ||
                (Regex("""(?:weight|ves|load|груз|масс)""", RegexOption.IGNORE_CASE).containsMatchIn(key) &&
                    !Regex("""dyn|динам|max|min|norm|id|name""", RegexOption.IGNORE_CASE).containsMatchIn(key))
        } ?: return null
        val rawStr = fields[k].orEmpty()
        var num = parseCoord(rawStr)
        if (num.isNaN()) return null
        if (Regex("""\bкг\b|\bkg\b""", RegexOption.IGNORE_CASE).containsMatchIn(rawStr) || num > 1000.0) {
            num /= 1000.0
        }
        return num
    }

    fun findFuelInFields(fields: Map<String, String>): Double? {
        val k = fields.keys.firstOrNull { key ->
            Regex("""^(fuel|dut|fls|lls|tank|topliv|топлив|бак|дут)$""", RegexOption.IGNORE_CASE).matches(key) ||
                (Regex("""(?:fuel|dut|fls|lls|topliv|топлив|бак)""", RegexOption.IGNORE_CASE).containsMatchIn(key) &&
                    !Regex("""rate|norm|cons|расход|max|cap|min|sensor|id|type|name|flag|enable|use""", RegexOption.IGNORE_CASE).containsMatchIn(key))
        } ?: return null
        val num = parseCoord(fields[k])
        if (num.isNaN() || num < 0.0 || num > 10000.0) return null
        return num
    }

    fun extractFuelFromRawFields(rawFields: Map<String, String>): String {
        val matches = mutableListOf<String>()
        for ((k, v) in rawFields) {
            val keyLower = k.lowercase(Locale.ROOT)
            if (Regex("""fuel|dut|fls|lls|tank|topliv|топлив|бак|дут""").containsMatchIn(keyLower) &&
                !Regex("""rate|norm|cons|расход|max|cap|min|sensor|id|type|name|flag|enable|use""").containsMatchIn(keyLower)
            ) {
                val cleaned = v.trim()
                if (cleaned.isNotEmpty() && !Regex("""^(null|undefined|false|true)$""", RegexOption.IGNORE_CASE).matches(cleaned)) {
                    val num = cleaned.replace(",", ".").toDoubleOrNull()
                    val formatted = if (num != null && !Regex("""[a-zа-я%]""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) {
                        "$cleaned л"
                    } else cleaned
                    matches.add(formatted)
                }
            }
        }
        return matches.joinToString(" / ")
    }

    fun findTimeInFields(fields: Map<String, String>): String {
        val exact = findByRegex(
            fields,
            Regex("""^(time|datetime|msgtime|gpstime|date|navtime|eventtime|dt|timestamp|loctime|t|tm|pttime|pointtime|tracktime|timeutc|timelocal|localtime|localdate|recdate|rectime|dtime)$""", RegexOption.IGNORE_CASE)
        )
        if (exact.isNotEmpty()) return exact
        val k2 = fields.keys.firstOrNull { Regex("""(?:time|date|^dt|tm)""", RegexOption.IGNORE_CASE).containsMatchIn(it) && !fields[it].isNullOrBlank() }
        if (k2 != null) return fields[k2].orEmpty()
        for (v in fields.values) {
            val s = v.trim()
            if (s.length <= 32 && Regex("""\d{1,2}:\d{2}(?::\d{2})?""").containsMatchIn(s)) return s
        }
        return ""
    }

    private val idRe = Regex("""^(id|obj|guid|uid|objectid|objid|unitid|carid|deviceid|code|imei|oid|pid|tsid|tcid)$""", RegexOption.IGNORE_CASE)
    private val nameRe = Regex("""^(name|objectname|objname|title|regnumber|gosnum|gosnumber|caption|vehiclename|carname|number|label|nm|garnum)$""", RegexOption.IGNORE_CASE)
    private val addrRe = Regex("""^(address|addr|location|place|geoaddr|lastaddress)$""", RegexOption.IGNORE_CASE)
    private val speedRe = Regex("""^(speed|spd|velocity|lastspeed|v|s)$""", RegexOption.IGNORE_CASE)
    private val courseRe = Regex("""^(course|azimuth|heading|dir|direction|angle|crs)$""", RegexOption.IGNORE_CASE)
    private val onlineRe = Regex("""^(online|isonline|connected|isconnected|link|haslink|valid|isvalid|active|isactive)$""", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("""^(time|lasttime|datetime|msgtime|gpstime|date|lastdate|eventtime|navtime|dt|timestamp|lastmsgtime|loctime)$""", RegexOption.IGNORE_CASE)
    private val durRe = Regex("""^(duration|statetime|stoptime|status|state)$""", RegexOption.IGNORE_CASE)
    private val falseRe = Regex("""^(false|0|no|off|нет)$""", RegexOption.IGNORE_CASE)

    private fun buildVehicleFromFields(fields: Map<String, String>, idx: Int): Vehicle? {
        val (latStr, lngStr) = findLatLng(fields)
        if (latStr.isEmpty() || lngStr.isEmpty()) return null
        val lat = parseCoord(latStr)
        val lng = parseCoord(lngStr)
        if (!isValidLatLng(lat, lng)) return null

        val idVal = findByRegex(fields, idRe).ifEmpty { idx.toString() }
        val nameVal = findByRegex(fields, nameRe).ifEmpty { "Объект #${idx + 1}" }
        val addrVal = findByRegex(fields, addrRe).ifEmpty {
            String.format(Locale.US, "%.5f, %.5f", lat, lng)
        }
        val speedVal = parseCoord(findByRegex(fields, speedRe)).let { if (it.isNaN()) 0.0 else it }
        val courseRaw = findByRegex(fields, courseRe)
        val courseVal = if (courseRaw.isNotEmpty()) parseCoord(courseRaw).takeUnless { it.isNaN() } else null
        val onlineRaw = findByRegex(fields, onlineRe)
        val online = !(onlineRaw.isNotEmpty() && falseRe.matches(onlineRaw))
        val active = online && speedVal > 2.0
        val durRaw = findByRegex(fields, durRe)
        val duration = if (durRaw.isNotEmpty() && !Regex("""^(true|false)$""", RegexOption.IGNORE_CASE).matches(durRaw)) {
            durRaw
        } else {
            if (active) "${speedVal.roundToInt()} км/ч" else if (online) "Стоянка" else "Нет связи"
        }
        val engineOff = parseEngineOffFromFields(fields, speedVal, duration)
        val timeVal = findByRegex(fields, timeRe).ifEmpty {
            SimpleDateFormat("HH:mm:ss", Locale("ru", "RU")).format(Date())
        }
        val weight = findWeightInFields(fields)
        val fuel = extractFuelFromRawFields(fields)
        val type = GeoAnalytics.classifyVehicleType(nameVal, fields)
        val isLoaded = GeoAnalytics.isLoadedByTelemetry(type, weight, fields)

        return Vehicle(
            id = idVal,
            name = nameVal,
            address = addrVal,
            lat = lat,
            lng = lng,
            speed = speedVal,
            course = courseVal,
            online = online,
            active = active,
            engineOff = engineOff,
            lastTime = timeVal,
            duration = duration,
            fuel = fuel,
            weight = weight,
            rawFields = fields,
            type = type,
            isLoaded = isLoaded
        )
    }

    fun extractVehicles(rawText: String): List<Vehicle> {
        val vehicles = mutableListOf<Vehicle>()

        // 1. Try JSON payload inside SOAP response
        runCatching {
            val jsonItems = extractJsonCandidates(rawText)
            jsonItems.forEachIndexed { idx, flat ->
                buildVehicleFromFields(flat, idx)?.let { vehicles.add(it) }
            }
        }
        if (vehicles.isNotEmpty()) return deduplicateVehicles(vehicles)

        // 2. Parse XML tree
        val tree = parseXmlTree(rawText)
        var idx = 0
        walkXmlNodes(tree) { node ->
            if (node.tag == "root" || Regex("""Response$|Result$|Envelope$|Body$""", RegexOption.IGNORE_CASE).containsMatchIn(node.tag)) {
                return@walkXmlNodes
            }
            val rawFields = collectNodeFields(node)
            if (rawFields.size < 2) return@walkXmlNodes
            buildVehicleFromFields(rawFields, idx)?.let {
                vehicles.add(it)
                idx++
            }
        }

        return deduplicateVehicles(vehicles)
    }

    private fun deduplicateVehicles(list: List<Vehicle>): List<Vehicle> {
        val seen = HashSet<String>()
        return list.filter { v ->
            val key = "${v.id}_${String.format(Locale.US, "%.5f_%.5f", v.lat, v.lng)}"
            seen.add(key)
        }
    }

    fun extractTrackPoints(rawText: String): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()

        runCatching {
            val jsonItems = extractJsonCandidates(rawText)
            for (flat in jsonItems) {
                val (latStr, lngStr) = findLatLng(flat)
                val lat = parseCoord(latStr)
                val lng = parseCoord(lngStr)
                if (isValidLatLng(lat, lng)) {
                    val spd = parseCoord(findByRegex(flat, Regex("""^(speed|spd|velocity|v|s)$""", RegexOption.IGNORE_CASE))).let { if (it.isNaN()) 0.0 else it }
                    points.add(
                        TrackPoint(
                            lat = lat,
                            lng = lng,
                            speed = spd,
                            time = findTimeInFields(flat),
                            weight = findWeightInFields(flat),
                            fuel = findFuelInFields(flat)
                        )
                    )
                }
            }
        }
        if (points.isNotEmpty()) return points

        val tree = parseXmlTree(rawText)
        walkXmlNodes(tree) { node ->
            if (node.tag == "root" || Regex("""Response$|Result$|Envelope$|Body$""", RegexOption.IGNORE_CASE).containsMatchIn(node.tag)) {
                return@walkXmlNodes
            }
            val fields = collectNodeFields(node)
            if (fields.isEmpty()) return@walkXmlNodes

            var (latStr, lngStr) = findLatLng(fields)
            if (latStr.isEmpty() || lngStr.isEmpty()) {
                if (latStr.isEmpty()) latStr = findByRegex(fields, Regex("""^(b|n)$""", RegexOption.IGNORE_CASE))
                if (lngStr.isEmpty()) lngStr = findByRegex(fields, Regex("""^(l|e)$""", RegexOption.IGNORE_CASE))
            }

            var lat = parseCoord(latStr)
            var lng = parseCoord(lngStr)

            if (!isValidLatLng(lat, lng)) {
                val pairStr = findByRegex(fields, Regex("""^(pos|coord|coords|point|pt|ll|latlon)$""", RegexOption.IGNORE_CASE))
                if (pairStr.isNotEmpty()) {
                    val parts = pairStr.split(Regex("""[,;\s]+""")).filter { it.isNotBlank() }.map { parseCoord(it) }
                    if (parts.size >= 2) {
                        lat = parts[0]
                        lng = parts[1]
                    }
                }
            }
            if (!isValidLatLng(lat, lng)) return@walkXmlNodes

            val spd = parseCoord(findByRegex(fields, Regex("""^(speed|spd|velocity|v|s)$""", RegexOption.IGNORE_CASE))).let { if (it.isNaN()) 0.0 else it }
            points.add(
                TrackPoint(
                    lat = lat,
                    lng = lng,
                    speed = spd,
                    time = findTimeInFields(fields),
                    weight = findWeightInFields(fields),
                    fuel = findFuelInFields(fields)
                )
            )
        }
        if (points.isNotEmpty()) return points

        // Fallback coordinate pair scan
        val clean = unescapeXml(rawText)
        val pairMatches = Regex("""\b\d{2}[.,]\d{4,7}[,;\s]+\d{2,3}[.,]\d{4,7}\b""").findAll(clean).toList()
        if (pairMatches.size >= 2) {
            for (pm in pairMatches) {
                val parts = pm.value.split(Regex("""[,;\s]+""")).map { parseCoord(it) }
                if (parts.size >= 2 && isValidLatLng(parts[0], parts[1])) {
                    points.add(TrackPoint(lat = parts[0], lng = parts[1], speed = 0.0, time = ""))
                }
            }
        }
        return points
    }

    fun extractSensors(rawText: String): List<SensorItem> {
        val sensors = mutableListOf<SensorItem>()
        val cleanText = unescapeXml(rawText)

        runCatching {
            val jsonArrays = Regex("""\[\s*\{[\s\S]*?\}\s*]""").findAll(cleanText)
            for (m in jsonArrays) {
                runCatching {
                    val arr = JSONArray(m.value)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val name = listOf("name", "Name", "SensorName", "Title", "Caption", "DisplayName")
                            .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() } }
                        val value = listOf("value", "Value", "Val", "TextValue", "CurValue", "CurrentValue", "State")
                            .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() } }
                        val unit = listOf("unit", "Unit", "Units", "Measure")
                            .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() } } ?: ""
                        if (name != null && value != null) {
                            sensors.add(SensorItem(name.trim(), (value.trim() + if (unit.isNotBlank()) " ${unit.trim()}" else "")))
                        }
                    }
                }
            }
        }

        val tree = parseXmlTree(rawText)
        walkXmlNodes(tree) { node ->
            if (node.tag == "root") return@walkXmlNodes
            val fields = collectNodeFields(node)
            if (fields.size < 2) return@walkXmlNodes

            val name = findByRegex(fields, Regex("""^(name|sensorname|title|caption|typename|displayname|paramname|desc|description)$""", RegexOption.IGNORE_CASE))
            val value = findByRegex(fields, Regex("""^(value|val|textvalue|state|strvalue|curvalue|currentvalue|lastvalue|fuelvalue|numvalue|rawvalue)$""", RegexOption.IGNORE_CASE))
            val unit = findByRegex(fields, Regex("""^(unit|units|measure|dimension|eu)$""", RegexOption.IGNORE_CASE))
            if (name.isNotEmpty() && value.isNotEmpty()) {
                sensors.add(SensorItem(name, if (unit.isNotEmpty()) "$value $unit" else value))
            }
        }

        val seen = HashSet<String>()
        return sensors.filter { seen.add("${it.name}_${it.value}") }
    }

    fun extractZones(rawText: String): List<GeoZone> {
        val zones = mutableListOf<GeoZone>()
        val tree = parseXmlTree(rawText)

        walkXmlNodes(tree) { node ->
            if (node.tag == "root" || Regex("""Response$|Result$|Envelope$|Body$""", RegexOption.IGNORE_CASE).containsMatchIn(node.tag)) {
                return@walkXmlNodes
            }
            val fields = collectNodeFields(node)
            val name = findByRegex(fields, Regex("""^(name|zonename|title|caption)$""", RegexOption.IGNORE_CASE))
                .ifEmpty { "Геозона #${zones.size + 1}" }
            val rawColor = findByRegex(fields, Regex("""^(color|fillcolor|linecolor)$""", RegexOption.IGNORE_CASE))
            val color = when {
                Regex("""^#[0-9a-fA-F]{6,8}$""").matches(rawColor) -> rawColor
                Regex("""^[0-9a-fA-F]{6}$""").matches(rawColor) -> "#$rawColor"
                else -> "#2563EB"
            }

            val coordsStr = findByRegex(fields, Regex("""^(points|coords|coordinates|geometry|polygon|path|wkt|vertexes|vertices)$""", RegexOption.IGNORE_CASE))
            if (coordsStr.isNotEmpty()) {
                val pairs = Regex("""-?\d+[.,]\d+[\s,]+-?\d+[.,]\d+""").findAll(coordsStr)
                val polyPts = mutableListOf<LatLngPoint>()
                for (pair in pairs) {
                    val nums = pair.value.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }.map { parseCoord(it) }
                    if (nums.size >= 2) {
                        if (isValidLatLng(nums[0], nums[1])) polyPts.add(LatLngPoint(nums[0], nums[1]))
                        else if (isValidLatLng(nums[1], nums[0])) polyPts.add(LatLngPoint(nums[1], nums[0]))
                    }
                }
                if (polyPts.size >= 3) {
                    val cLat = polyPts.map { it.lat }.average()
                    val cLng = polyPts.map { it.lng }.average()
                    zones.add(
                        GeoZone(
                            id = "srv_${zones.size}_$name",
                            key = "srv_$name",
                            name = name,
                            shortName = name,
                            colorHex = color,
                            kind = GeoZoneKind.SERVER,
                            isCircle = false,
                            centerLat = cLat,
                            centerLng = cLng,
                            polygon = polyPts
                        )
                    )
                    return@walkXmlNodes
                }
            }

            val subPoints = mutableListOf<LatLngPoint>()
            walkXmlNodes(node) { sub ->
                if (sub === node) return@walkXmlNodes
                val sf = collectNodeFields(sub)
                val la = parseCoord(findByRegex(sf, Regex("""^(lat|latitude|y)$""", RegexOption.IGNORE_CASE)))
                val lo = parseCoord(findByRegex(sf, Regex("""^(lon|lng|longitude|x)$""", RegexOption.IGNORE_CASE)))
                if (isValidLatLng(la, lo)) subPoints.add(LatLngPoint(la, lo))
            }
            if (subPoints.size >= 3 && node.children.size <= 20) {
                val cLat = subPoints.map { it.lat }.average()
                val cLng = subPoints.map { it.lng }.average()
                zones.add(
                    GeoZone(
                        id = "srv_${zones.size}_$name",
                        key = "srv_$name",
                        name = name,
                        shortName = name,
                        colorHex = color,
                        kind = GeoZoneKind.SERVER,
                        isCircle = false,
                        centerLat = cLat,
                        centerLng = cLng,
                        polygon = subPoints
                    )
                )
                return@walkXmlNodes
            }

            val rad = parseCoord(findByRegex(fields, Regex("""^(radius|rad|r)$""", RegexOption.IGNORE_CASE)))
            val lat = parseCoord(findByRegex(fields, Regex("""^(lat|latitude|y|centerlat)$""", RegexOption.IGNORE_CASE)))
            val lng = parseCoord(findByRegex(fields, Regex("""^(lon|lng|longitude|x|centerlon)$""", RegexOption.IGNORE_CASE)))
            if (isValidLatLng(lat, lng) && !rad.isNaN() && rad > 0.0) {
                zones.add(
                    GeoZone(
                        id = "srv_${zones.size}_$name",
                        key = "srv_$name",
                        name = name,
                        shortName = name,
                        colorHex = color,
                        kind = GeoZoneKind.SERVER,
                        isCircle = true,
                        centerLat = lat,
                        centerLng = lng,
                        radiusMeters = rad
                    )
                )
            }
        }

        val seen = HashSet<String>()
        return zones.filter { z ->
            val key = if (z.isCircle) {
                "c_${z.name}_${String.format(Locale.US, "%.4f_%.4f", z.centerLat, z.centerLng)}"
            } else {
                val p0 = z.polygon.firstOrNull()
                "p_${z.name}_${z.polygon.size}_${String.format(Locale.US, "%.4f", p0?.lat ?: 0.0)}"
            }
            seen.add(key)
        }
    }
}

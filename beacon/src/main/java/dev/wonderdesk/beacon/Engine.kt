package dev.wonderdesk.beacon

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.wonderdesk.shared.Expression
import java.io.File
import kotlin.math.abs

/** One search result. [intent] opens it. */
data class Hit(val kind: String, val title: String, val subtitle: String, val intent: Intent?, val score: Int, val path: String? = null)

/**
 * Beacon's search engine, shared by Beacon's own screen and every Wonderdesk app (through
 * [SearchProvider]): maths, unit conversion, Android settings, contacts, files and the words inside
 * them, and the web.
 */
object Engine {
    data class Setting(val name: String, val words: String, val action: String)

    val SETTINGS = listOf(
        Setting("Wi-Fi", "wifi wireless internet network hotspot", Settings.ACTION_WIFI_SETTINGS), Setting("Bluetooth", "bluetooth pair headphones speaker car", Settings.ACTION_BLUETOOTH_SETTINGS),
        Setting("Mobile network", "mobile data cellular sim carrier roaming 5g lte", Settings.ACTION_NETWORK_OPERATOR_SETTINGS), Setting("Data usage", "data usage limit saver", Settings.ACTION_DATA_USAGE_SETTINGS),
        Setting("Hotspot and tethering", "hotspot tether share internet", "android.settings.TETHER_SETTINGS"), Setting("Airplane mode", "airplane flight mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Setting("VPN", "vpn private network", Settings.ACTION_VPN_SETTINGS), Setting("NFC", "nfc tap pay contactless", Settings.ACTION_NFC_SETTINGS),
        Setting("Display", "display brightness screen timeout", Settings.ACTION_DISPLAY_SETTINGS), Setting("Dark theme", "dark theme night mode", "android.settings.DARK_THEME_SETTINGS"),
        Setting("Night Light", "night light blue light warm", "android.settings.NIGHT_DISPLAY_SETTINGS"), Setting("Font size", "font text size bigger smaller", "android.settings.TEXT_READING_SETTINGS"),
        Setting("Wallpaper", "wallpaper background style", Intent.ACTION_SET_WALLPAPER), Setting("Sound and vibration", "sound volume ringtone vibrate silent", Settings.ACTION_SOUND_SETTINGS),
        Setting("Do Not Disturb", "do not disturb dnd quiet focus", "android.settings.ZEN_MODE_SETTINGS"), Setting("Notifications", "notifications alerts badges", "android.settings.NOTIFICATION_SETTINGS"),
        Setting("Battery", "battery power usage", Intent.ACTION_POWER_USAGE_SUMMARY), Setting("Battery Saver", "battery saver power save", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Setting("Storage", "storage space free up memory", Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Setting("Apps", "apps applications uninstall permissions force stop", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        Setting("Default apps", "default browser launcher home phone sms", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Setting("Permission manager", "permission camera microphone location privacy", "android.intent.action.MANAGE_PERMISSIONS"),
        Setting("Location", "location gps maps", Settings.ACTION_LOCATION_SOURCE_SETTINGS), Setting("Security", "security lock screen pin password pattern", Settings.ACTION_SECURITY_SETTINGS),
        Setting("Fingerprint", "fingerprint biometric face unlock", "android.settings.FINGERPRINT_ENROLL"), Setting("Privacy", "privacy dashboard", Settings.ACTION_PRIVACY_SETTINGS),
        Setting("Passwords and accounts", "accounts passwords google sync autofill", Settings.ACTION_SYNC_SETTINGS), Setting("Autofill service", "autofill passwords vault", Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE),
        Setting("Accessibility", "accessibility talkback magnify captions", Settings.ACTION_ACCESSIBILITY_SETTINGS), Setting("Date and time", "date time zone clock 24 hour", Settings.ACTION_DATE_SETTINGS),
        Setting("Languages", "language region locale", Settings.ACTION_LOCALE_SETTINGS), Setting("Keyboard", "keyboard input gboard typing", Settings.ACTION_INPUT_METHOD_SETTINGS),
        Setting("Backup", "backup restore", "android.settings.BACKUP_AND_RESET_SETTINGS"), Setting("System update", "system update android version", "android.settings.SYSTEM_UPDATE_SETTINGS"),
        Setting("Reset options", "reset factory erase", "android.settings.BACKUP_AND_RESET_SETTINGS"), Setting("About phone", "about phone model android version build imei", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Setting("Developer options", "developer usb debugging animation", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Setting("Usage access", "usage access screen time", Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Setting("All files access", "all files access storage permission", Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), Setting("Install unknown apps", "install unknown apps sources apk", Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        Setting("Home app", "home launcher default", Settings.ACTION_HOME_SETTINGS), Setting("Cast", "cast screen mirror tv", Settings.ACTION_CAST_SETTINGS),
        Setting("Printing", "print printer", Settings.ACTION_PRINT_SETTINGS), Setting("Screen lock", "screen lock pin", "android.app.action.SET_NEW_PASSWORD"),
    )

    /* ---------------------------------------------- maths and units ---------------------------------------------- */

    private val UNIT_TABLE: Map<String, Pair<String, Double>> = buildMap {
        fun u(cat: String, f: Double, vararg names: String) = names.forEach { put(it, cat to f) }
        u("len", 1.0, "m", "meter", "meters", "metre", "metres"); u("len", 1000.0, "km", "kilometer", "kilometers", "kilometre", "kilometres")
        u("len", .01, "cm", "centimeter", "centimeters"); u("len", .001, "mm", "millimeter", "millimeters"); u("len", 1609.344, "mi", "mile", "miles")
        u("len", .3048, "ft", "foot", "feet"); u("len", .0254, "in", "inch", "inches"); u("len", .9144, "yd", "yard", "yards"); u("len", 1852.0, "nmi", "nautical")
        u("mass", 1.0, "kg", "kilogram", "kilograms", "kilo", "kilos"); u("mass", .001, "g", "gram", "grams"); u("mass", .45359237, "lb", "lbs", "pound", "pounds")
        u("mass", .028349523125, "oz", "ounce", "ounces"); u("mass", 6.35029318, "st", "stone"); u("mass", 1000.0, "t", "tonne", "tonnes", "ton")
        u("vol", 1.0, "l", "liter", "liters", "litre", "litres"); u("vol", .001, "ml", "milliliter", "milliliters", "millilitre"); u("vol", 3.785411784, "gal", "gallon", "gallons")
        u("vol", .2365882365, "cup", "cups"); u("vol", .0147867648, "tbsp", "tablespoon", "tablespoons"); u("vol", .00492892159, "tsp", "teaspoon", "teaspoons")
        u("vol", .0295735295625, "floz"); u("vol", .473176473, "pint", "pints"); u("vol", .946352946, "quart", "quarts")
        u("speed", 1.0, "m/s", "mps"); u("speed", 1 / 3.6, "km/h", "kmh", "kph"); u("speed", .44704, "mph"); u("speed", .514444, "knot", "knots", "kn")
        u("time", 1.0, "s", "sec", "second", "seconds"); u("time", 60.0, "min", "minute", "minutes"); u("time", 3600.0, "h", "hr", "hour", "hours")
        u("time", 86400.0, "day", "days"); u("time", 604800.0, "week", "weeks"); u("time", 31557600.0, "year", "years")
        u("data", 1.0, "b", "byte", "bytes"); u("data", 1024.0, "kb", "kib"); u("data", 1048576.0, "mb", "mib"); u("data", 1073741824.0, "gb", "gib"); u("data", 1099511627776.0, "tb", "tib")
        u("area", 1.0, "m2", "sqm"); u("area", 4046.8564224, "acre", "acres"); u("area", 10000.0, "ha", "hectare", "hectares"); u("area", .09290304, "ft2", "sqft")
        u("energy", 1.0, "j", "joule", "joules"); u("energy", 4184.0, "kcal", "calorie", "calories"); u("energy", 3600000.0, "kwh")
    }
    private val TEMPS = mapOf("c" to "C", "celsius" to "C", "°c" to "C", "f" to "F", "fahrenheit" to "F", "°f" to "F", "k" to "K", "kelvin" to "K")
    private val CONVERT = Regex("^\\s*(-?[0-9.,]+)\\s*([a-z°/0-9]+)\\s+(?:to|in|as|=)\\s+([a-z°/0-9]+)\\s*$", RegexOption.IGNORE_CASE)

    private fun nice(v: Double): String = if (abs(v) >= 1e15 || (abs(v) < 1e-6 && v != 0.0)) "%.6g".format(v) else Expression.format(Math.round(v * 1e8) / 1e8)

    fun convert(q: String): Hit? {
        val m = CONVERT.find(q.lowercase()) ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val a = m.groupValues[2]; val b = m.groupValues[3]
        val ta = TEMPS[a]; val tb = TEMPS[b]
        val result = if (ta != null && tb != null) {
            val c = when (ta) { "C" -> n; "F" -> (n - 32) * 5 / 9; else -> n - 273.15 }
            when (tb) { "C" -> c; "F" -> c * 9 / 5 + 32; else -> c + 273.15 }
        } else {
            val ua = UNIT_TABLE[a] ?: return null; val ub = UNIT_TABLE[b] ?: return null
            if (ua.first != ub.first) return null
            n * ua.second / ub.second
        }
        val text = "${nice(n)} $a = ${nice(result)} $b"
        return Hit("convert", "${nice(result)} $b", "${nice(n)} $a", copyIntent(text), 1000)
    }

    /** Sums, with friendly input: ×, ÷, "15% of 80", "3 times 4". */
    fun math(q: String): Hit? {
        var s = q.trim().lowercase().replace('×', '*').replace('÷', '/').replace('−', '-').replace(" times ", "*").replace(" plus ", "+").replace(" minus ", "-").replace(" divided by ", "/").replace(",", "")
        s = Regex("([0-9.]+)\\s*%\\s*of\\s*([0-9.]+)").replace(s) { "(${it.groupValues[1]}/100*${it.groupValues[2]})" }
        s = Regex("([0-9.]+)\\s*%").replace(s) { "(${it.groupValues[1]}/100)" }
        s = s.removePrefix("=").removeSuffix("=").trim()
        if (s.none { it in "+-*/^()" } && "sqrt" !in s) return null
        if (s.any { it.isLetter() && it !in "sqrtpie" }) return null
        val v = runCatching { Expression.evaluate(s) }.getOrNull()?.takeIf { it.isFinite() } ?: return null
        val out = nice(v)
        return Hit("math", out, q.trim() + " =", copyIntent(out), 1100)
    }

    private fun copyIntent(text: String) = Intent("dev.wonderdesk.beacon.COPY").setClassName("dev.wonderdesk.beacon", "dev.wonderdesk.beacon.OpenActivity").putExtra("text", text)

    /* -------------------------------------------------- everything ------------------------------------------------- */

    fun settings(q: String): List<Hit> {
        val t = q.trim().lowercase()
        return SETTINGS.mapNotNull { st ->
            val score = when {
                st.name.lowercase() == t -> 900
                st.name.lowercase().startsWith(t) -> 700
                st.words.split(' ').any { it.startsWith(t) } -> 500
                t.length >= 3 && st.words.contains(t) -> 300
                else -> 0
            }
            if (score == 0) null else Hit("setting", st.name, "Settings", Intent(st.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), score)
        }.sortedByDescending { it.score }.take(5)
    }

    fun contacts(c: Context, q: String): List<Hit> {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED || q.length < 2) return emptyList()
        return runCatching {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?", arrayOf("%$q%", "%$q%"), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cur ->
                val out = ArrayList<Hit>(); val seen = HashSet<Long>()
                while (cur.moveToNext() && out.size < 8) {
                    val id = cur.getLong(0); if (!seen.add(id)) continue
                    val name = cur.getString(1) ?: ""
                    out += Hit("contact", name, cur.getString(2) ?: "", Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        if (name.startsWith(q, true)) 650 else 450)
                }
                out
            }
        }.getOrNull().orEmpty()
    }

    fun files(c: Context, q: String): List<Hit> {
        if (q.length < 2) return emptyList()
        val index = FileIndex(c)
        val byName = runCatching { index.byName(q, 20) }.getOrDefault(emptyList()).map { h ->
            Hit("file", h.name, (File(h.path).parent ?: "").removePrefix(android.os.Environment.getExternalStorageDirectory().path).ifBlank { "/" }, openIntent(h.path), if (h.name.startsWith(q, true)) 420 else 320, h.path)
        }
        val inside = runCatching { index.inside(q, 12) }.getOrDefault(emptyList()).map { h ->
            Hit("content", h.name, h.snippet?.replace('\n', ' ')?.replace("[", "")?.replace("]", "")?.take(120) ?: "", openIntent(h.path), 250, h.path)
        }
        return byName + inside.filter { i -> byName.none { it.path == i.path } }
    }

    /** Opens a file through Beacon, which owns the file permission and picks the right Wonderdesk app. */
    fun openIntent(path: String) = Intent("dev.wonderdesk.beacon.OPEN").setClassName("dev.wonderdesk.beacon", "dev.wonderdesk.beacon.OpenActivity").putExtra("path", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun web(q: String) = Hit("web", "Search the web for “${q.trim()}”", "DuckDuckGo", Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q.trim()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 10)

    /** Everything except apps, best first. */
    fun search(c: Context, q: String): List<Hit> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        return (listOfNotNull(convert(t), math(t)) + settings(t) + contacts(c, t) + files(c, t) + web(t)).sortedByDescending { it.score }
    }
}

/**
 * Search for the other Wonderdesk apps (the Launcher's Launchpad and widgets). Results can include
 * contacts and file names, so only Wonderdesk apps and adb may ask.
 */
class SearchProvider : ContentProvider() {
    override fun onCreate() = true

    private fun allowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SHELL_UID) return true
        return context?.packageManager?.getPackagesForUid(uid).orEmpty().any { it.startsWith("dev.wonderdesk.") }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        if (!allowed()) return null
        val q = selection ?: uri.getQueryParameter("q") ?: return null
        val c = MatrixCursor(arrayOf("kind", "title", "subtitle", "intent", "score"))
        val id = Binder.clearCallingIdentity()
        try { Engine.search(context!!, q).take(40).forEach { h -> c.addRow(arrayOf(h.kind, h.title, h.subtitle, h.intent?.toUri(Intent.URI_INTENT_SCHEME), h.score)) } }
        finally { Binder.restoreCallingIdentity(id) }
        return c
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** Opens a search result for another app: a file (with the right Wonderdesk app) or copies an answer. */
class OpenActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("text")?.let { t ->
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Beacon", t))
            android.widget.Toast.makeText(this, "Copied $t", android.widget.Toast.LENGTH_SHORT).show()
        }
        intent.getStringExtra("path")?.let { p -> val f = File(p); if (f.isDirectory) showInWayfinder(this, p) else openFile(this, f) }
        finish()
    }
}

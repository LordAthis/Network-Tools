// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A tesztek legutóbbi futásának összefoglalói, hálózatonként (`networkKey`) csoportosítva - EZ adja
 * a Gyorsjelentés panel alsó kereténél a "hány élő host, hány gyanús port, mikor futott utoljára
 * melyik teszt" adatot, HÁLÓZATRA SZŰRVE (a más hálózaton futott tesztek nem keverednek bele).
 */
class QuickReportStore(private val dir: File) {
    private val file = File(dir, "quickreport.json")

    @Synchronized
    fun loadAll(): List<TestRunSummary> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Egy teszt eredményének elmentése - ha ugyanaz a (networkKey, testId) már szerepel, felülírja. */
    @Synchronized
    fun record(summary: TestRunSummary) {
        val list = loadAll().filterNot { it.networkKey == summary.networkKey && it.testId == summary.testId } + summary
        dir.mkdirs()
        val arr = JSONArray()
        for (s in list) arr.put(encode(s))
        file.writeText(arr.toString(), Charsets.UTF_8)
    }

    fun forNetwork(networkKey: String): List<TestRunSummary> = loadAll().filter { it.networkKey == networkKey }

    private fun encode(s: TestRunSummary): JSONObject = JSONObject().apply {
        put("testId", s.testId)
        put("testName", s.testName)
        put("shortCode", s.shortCode)
        put("networkKey", s.networkKey)
        put("finishedMs", s.finishedMs)
        put("headline", s.headline)
        put("logFileName", s.logFileName ?: JSONObject.NULL)
    }

    private fun decode(o: JSONObject): TestRunSummary = TestRunSummary(
        testId = o.getString("testId"),
        testName = o.getString("testName"),
        shortCode = o.getString("shortCode"),
        networkKey = o.getString("networkKey"),
        finishedMs = o.getLong("finishedMs"),
        headline = o.getString("headline"),
        logFileName = o.optString("logFileName", null).takeIf { !o.isNull("logFileName") },
    )
}

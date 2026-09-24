// Verzio: v0.6.0 - 2026-09-24
package hu.lordathis.networktools.engine

import android.content.Context
import org.json.JSONObject

/** Egy teszt bejegyzés a katalógusban (`assets/tests_catalog.json`). */
data class TestDef(val id: String, val name: String, val shortCode: String, val groupId: String)

data class TestGroup(val id: String, val name: String, val tests: List<TestDef>)

/**
 * A tesztek NEVE és RÖVID KÓDJA egy közös JSON-fájlban van (nem a kódba égetve). Két kategória:
 *  - [autoTests]: egyszerű, gyors, alacsony hatású tesztek - induláskor és (a beállított
 *    időközönként) automatikusan lefutnak, NEM jelennek meg kézi FUTTATÁS gombbal.
 *  - [groups]: a többi, hosszabb/nagyobb hatású teszt - ezek maradnak a kézi, bal oldali fiókokban.
 * A besorolás indoklása: lásd a "teszt-rangsorolási elemzés" dokumentumot.
 */
object TestCatalog {
    private var cached: Triple<List<TestDef>, List<TestGroup>, TestDef>? = null // (auto, manual csoportok, arp_spike)

    fun autoTests(context: Context): List<TestDef> = loaded(context).first
    fun groups(context: Context): List<TestGroup> = loaded(context).second
    fun arpSpike(context: Context): TestDef = loaded(context).third

    fun find(context: Context, testId: String): TestDef? =
        autoTests(context).firstOrNull { it.id == testId }
            ?: groups(context).flatMap { it.tests }.firstOrNull { it.id == testId }
            ?: arpSpike(context).takeIf { it.id == testId }

    private fun parseTestList(arr: org.json.JSONArray, groupId: String): List<TestDef> =
        (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            TestDef(t.getString("id"), t.getString("name"), t.getString("shortCode"), groupId)
        }

    private fun loaded(context: Context): Triple<List<TestDef>, List<TestGroup>, TestDef> {
        cached?.let { return it }
        val text = context.assets.open("tests_catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)

        val autoArr = root.optJSONArray("auto_tests")
        val autoTests = if (autoArr != null) parseTestList(autoArr, "auto") else emptyList()

        val groupsArr = root.getJSONArray("manual_groups")
        val groups = (0 until groupsArr.length()).map { gi ->
            val g = groupsArr.getJSONObject(gi)
            val groupId = g.getString("id")
            TestGroup(groupId, g.getString("name"), parseTestList(g.getJSONArray("tests"), groupId))
        }

        val arpObj = root.getJSONObject("arp_spike")
        val arp = TestDef(arpObj.getString("id"), arpObj.getString("name"), arpObj.getString("shortCode"), "ARP")

        val result = Triple(autoTests, groups, arp)
        cached = result
        return result
    }
}

// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.engine

import android.content.Context
import org.json.JSONObject

/** Egy teszt bejegyzés a katalógusban (`assets/tests_catalog.json`). */
data class TestDef(val id: String, val name: String, val shortCode: String, val groupId: String)

data class TestGroup(val id: String, val name: String, val tests: List<TestDef>)

/**
 * A tesztek NEVE és RÖVID KÓDJA egy közös JSON-fájlban van (nem a kódba égetve), hogy a
 * lapfülek/panelek feliratai egy helyről, könnyen bővíthetők/átnevezhetők legyenek - ahogy a
 * Launcher.ps1 menüpontjai adják a Windows-os elnevezéseket.
 */
object TestCatalog {
    private var cached: Pair<List<TestGroup>, TestDef>? = null // (csoportok, arp_spike)

    fun groups(context: Context): List<TestGroup> = loaded(context).first
    fun arpSpike(context: Context): TestDef = loaded(context).second

    fun find(context: Context, testId: String): TestDef? =
        groups(context).flatMap { it.tests }.firstOrNull { it.id == testId }
            ?: arpSpike(context).takeIf { it.id == testId }

    private fun loaded(context: Context): Pair<List<TestGroup>, TestDef> {
        cached?.let { return it }
        val text = context.assets.open("tests_catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val groupsArr = root.getJSONArray("groups")
        val groups = (0 until groupsArr.length()).map { gi ->
            val g = groupsArr.getJSONObject(gi)
            val groupId = g.getString("id")
            val testsArr = g.getJSONArray("tests")
            val tests = (0 until testsArr.length()).map { ti ->
                val t = testsArr.getJSONObject(ti)
                TestDef(t.getString("id"), t.getString("name"), t.getString("shortCode"), groupId)
            }
            TestGroup(groupId, g.getString("name"), tests)
        }
        val arpObj = root.getJSONObject("arp_spike")
        val arp = TestDef(arpObj.getString("id"), arpObj.getString("name"), arpObj.getString("shortCode"), "ARP")
        val result = groups to arp
        cached = result
        return result
    }
}

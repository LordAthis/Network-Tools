// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Egy a Webolvasóban megnyitott cím. */
data class WebVisit(val url: String, val title: String, val firstMs: Long, val lastMs: Long, val count: Int)

/**
 * A Webolvasóban megnyitott címek előzménye (profiles/web_history.json - így az Adatmentés része).
 * Csak cím + oldalcím + időpontok, semmilyen oldal-tartalom vagy süti.
 */
class WebHistoryStore(private val file: File) {

    @Synchronized
    fun loadAll(): List<WebVisit> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                WebVisit(it.optString("url"), it.optString("title"), it.optLong("firstMs"), it.optLong("lastMs"), it.optInt("count", 1))
            }.sortedByDescending { it.lastMs }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun record(url: String, title: String?) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        val now = System.currentTimeMillis()
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(title = title?.takeIf { it.isNotBlank() } ?: old.title, lastMs = now, count = old.count + 1)
        } else {
            list += WebVisit(url, title.orEmpty(), now, now, 1)
        }
        save(list.sortedByDescending { it.lastMs }.take(MAX))
    }

    /** Csak az oldalcím frissítése (a cím betöltése után érkezik), a számláló növelése nélkül. */
    @Synchronized
    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        val list = loadAll()
        if (list.none { it.url == url }) return
        save(list.map { if (it.url == url) it.copy(title = title) else it })
    }

    @Synchronized
    fun delete(url: String) = save(loadAll().filterNot { it.url == url })

    private fun save(list: List<WebVisit>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("url", it.url)
                put("title", it.title)
                put("firstMs", it.firstMs)
                put("lastMs", it.lastMs)
                put("count", it.count)
            })
        }
        file.writeText(arr.toString(), Charsets.UTF_8)
    }

    private companion object {
        const val MAX = 500
    }
}

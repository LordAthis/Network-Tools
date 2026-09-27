// Verzio: v0.6.0 - 2026-09-27
package hu.lordathis.networktools.engine

enum class JobStatus { RUNNING, DONE, FAILED }

/**
 * Egy futó/lezárt teszt állapota - a VISSZAJELZÉSEK panel felső (terminál-szerű) sávja ezt mutatja,
 * lapfüllel, ha egyszerre több fut. A [lines] a "terminálon" megjelenő szöveg, sorról sorra bővül,
 * amíg [status] RUNNING.
 */
data class TestJob(
    val jobId: String,
    val testId: String,
    val label: String,
    val shortCode: String,
    val status: JobStatus,
    val startedMs: Long,
    val finishedMs: Long? = null,
    val lines: List<String> = emptyList(),
)

/**
 * Egy teszt részletes paneljének (a bal fiók sorára koppintva) KORÁBBI adatai: a legutóbbi elmentett
 * átirat (log/tests/), és a tesztek által a hálózaton látott eszközök (profiles/lan_hosts.json).
 */
data class TestDetailData(
    val transcript: List<String> = emptyList(),
    val transcriptFile: String? = null,
    val transcriptTimeMs: Long? = null,
    val hosts: List<hu.lordathis.networktools.speed.LanHostInfo> = emptyList(),
)

/** Egy teszt legutóbbi futásának rövid összefoglalója (a Gyorsjelentés alsó kerete ezekből épül). */
data class TestRunSummary(
    val testId: String,
    val testName: String,
    val shortCode: String,
    val networkKey: String,
    val finishedMs: Long,
    val headline: String,
    val logFileName: String?,
)

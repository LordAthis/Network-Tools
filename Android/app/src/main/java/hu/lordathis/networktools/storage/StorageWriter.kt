// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A "mentés" folyamat: egyetlen, saját (egyszálas) dispatcheren futó író, ami a naplót sorrendben,
 * egymás után írja lemezre. A lemez-I/O így SOHA nem a UI-szálon történik, és a sorrend megmarad.
 */
class StorageWriter(
    dispatcher: CoroutineDispatcher,
    private val logStore: LogStore,
    private val onError: (String) -> Unit,
) {
    private val channel = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val job = scope.launch {
        for (line in channel) {
            try {
                logStore.append(line)
            } catch (e: Exception) {
                onError("Mentési hiba: ${e.message}")
            }
        }
    }

    fun log(line: String) {
        channel.trySend(line)
    }

    /** Lezárja a sort; a már beérkezett bejegyzéseket még kiírja, utána hívja az [onDone]-t. */
    fun close(onDone: () -> Unit) {
        channel.close()
        job.invokeOnCompletion { onDone() }
    }
}

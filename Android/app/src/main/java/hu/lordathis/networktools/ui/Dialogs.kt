// Verzio: v0.4.0 - 2026-09-21
package hu.lordathis.networktools.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/** Megerősítő párbeszédablak (törlésekhez). */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, fontSize = 13.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = DangerColor) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("MÉGSEM", color = TextDim) } },
    )
}

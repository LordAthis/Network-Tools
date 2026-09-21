// Verzio: v0.3.0 - 2026-09-21
package hu.lordathis.networktools.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.notes.NoteItem

/** Keretes szakasz: felirat + tartalom, belső kerettel. */
@Composable
private fun Frame(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Accent.copy(alpha = 0.45f), shape)
            .padding(12.dp)
    ) {
        Text(title, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SmallButton(label: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(label, color = color, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

/**
 * JEGYZET panel: fent a "+ ÚJ JEGYZET" gomb - erre megjelenik a beviteli keret (Cím, Tartalom, Mentés / Mégsem);
 * alatta a már mentett jegyzetek listája (legújabb elöl), mindegyik mellett Módosítás és Törlés gombbal.
 * Egy jegyzet egy .md fájl (cím, dátum, tartalom) - a mappa a lista alatt látszik.
 */
@Composable
internal fun NotesStripedPanel(
    modifier: Modifier,
    notes: List<NoteItem>,
    notesFolder: String,
    onSave: (id: String?, title: String, content: String) -> Unit,
    onDelete: (NoteItem) -> Unit,
) {
    val scroll = rememberScrollState()
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    // Módosításkor / új jegyzetnél a szerkesztő látható legyen (a lista tetejére görgetés)
    LaunchedEffect(editorOpen, editingId) {
        if (editorOpen) scroll.animateScrollTo(0)
    }

    val closeEditor: () -> Unit = {
        editorOpen = false
        editingId = null
        title = ""
        content = ""
    }

    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec(
            label = "+ ÚJ JEGYZET",
            enabled = !editorOpen,
            onClick = {
                editingId = null
                title = ""
                content = ""
                editorOpen = true
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("JEGYZET", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            // ------------------------------------------------------------ Szerkesztő
            if (editorOpen) {
                Frame(if (editingId != null) "JEGYZET MÓDOSÍTÁSA" else "ÚJ JEGYZET") {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Cím") },
                        singleLine = true,
                        colors = appFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Tartalom") },
                        minLines = 4,
                        maxLines = 10,
                        colors = appFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "A dátumot az app adja hozzá (létrehozáskor a mostani idő; módosításkor az eredeti megmarad).",
                        color = TextDim,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PillButton("MENTÉS", enabled = title.isNotBlank(), filled = true) {
                            onSave(editingId, title.trim(), content.trim())
                            closeEditor()
                        }
                        Spacer(Modifier.width(12.dp))
                        PillButton("MÉGSEM", enabled = true, filled = false, onClick = closeEditor)
                    }
                }
            }

            // ------------------------------------------------------------ Mentett jegyzetek
            Frame("MENTETT JEGYZETEK (${notes.size})") {
                if (notes.isEmpty()) {
                    Text("Még nincs jegyzet. A fenti \"+ ÚJ JEGYZET\" gombbal vehetsz fel egyet.", color = TextDim, fontSize = 11.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        notes.forEach { note ->
                            NoteRow(
                                note = note,
                                onEdit = {
                                    editingId = note.id
                                    title = note.title
                                    content = note.content
                                    editorOpen = true
                                },
                                onDelete = { onDelete(note) }
                            )
                        }
                    }
                }
                Text(
                    "Egy jegyzet egy .md fájl (cím, dátum, tartalom): $notesFolder",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun NoteRow(note: NoteItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Accent.copy(alpha = 0.5f), shape)
            .padding(10.dp)
    ) {
        Text(note.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(formatDateTime(note.createdMs), color = TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        if (note.content.isNotBlank()) {
            Text(
                note.content,
                color = TextDim,
                fontSize = 10.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("MÓDOSÍTÁS", AccentBlue, onEdit)
            SmallButton("TÖRLÉS", DangerColor, onDelete)
        }
    }
}

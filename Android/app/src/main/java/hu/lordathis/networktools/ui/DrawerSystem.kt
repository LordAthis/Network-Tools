// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Oldalsó fiókok: két, szél-húzással (vagy a fogantyúra koppintással) nyitható keskeny fiók, ikon-gombokkal.
//
// A korábbi hibák oka:
//  * a nyitottság a KÉPERNYŐ szélességéhez volt normalizálva, miközben a fiók csak
//    ~46dp széles: a fiók csak a képernyő több mint felének végighúzása után lett
//    látható és "nyitott" - ezért tűnt véletlenszerűnek;
//  * a húzás-érzékelő a tartalom MÖGÖTT volt, így a gombok/szövegmező elnyelték;
//  * nyitott fiókot nem lehetett bezárni.
//
// Az új működés:
//  * nyitottság = a FIÓK (panel) szélességéhez viszonyítva: 0f zárva, 1f nyitva;
//  * egyetlen, gyökér-szintű, "Initial" menetű húzás-figyelő: zárt fióknál CSAK a
//    szélső sávban (a képernyő ~26%-a) és csak BEFELÉ húzást figyel, nyitottnál
//    bárhol csak KIFELÉ húzást; a koppintások/görgetések érintetlenek maradnak;
//  * a húzás szinkron állapotot ír (nincs coroutine-indítás húzás közben), elengedéskor
//    EGYETLEN "beállás" animáció fut (sebesség vagy fél-út alapján);
//  * bezárás: kifelé húzás, koppintás a fiókon kívülre (scrim), koppintás a fogantyúra,
//    vissza gomb/gesztus.
// ---------------------------------------------------------------------------

internal const val DRAWER_PANEL_DP = 46

// A húzás-figyelő sávja (a főképernyő dobozához képest, a 2026-09-19-i jelölések alapján).
private const val ZONE_WIDTH = 0.26f
private const val ZONE_TOP = 0.04f
private const val ZONE_BOTTOM = 0.70f

// A fogantyú (hosszú csík) függőleges helye: fent 9%, magassága 61%, alul a maradék 30%.
private const val HANDLE_TOP_WEIGHT = 0.09f
private const val HANDLE_STRIP_WEIGHT = 0.61f
private const val HANDLE_BOTTOM_WEIGHT = 0.30f
private const val HANDLE_BOX_DP = 16
private const val HANDLE_VISUAL_DP = 10

// Gyors elhúzás (px/s), ami fél-út alatt/felett is a húzás irányába dönt.
private const val FLING_VELOCITY = 700f

@Stable
internal class EdgeDrawerState(
    private val scope: CoroutineScope,
    private val panelWidthPx: Float,
) {
    /** 0f = zárva, 1f = teljesen nyitva (a panel szélességéhez viszonyítva). */
    var openness by mutableFloatStateOf(0f)
        private set

    /** Igaz, amíg a fiók bármennyire is látszik (a kompozíció csak az átbillenést figyeli). */
    val isOpen: Boolean by derivedStateOf { openness > 0.001f }

    private var settleJob: Job? = null

    fun dragStart() {
        settleJob?.cancel()
        settleJob = null
    }

    /** Szinkron állapotírás húzás közben (nincs coroutine). */
    fun dragBy(deltaPx: Float) {
        openness = (openness + deltaPx / panelWidthPx).coerceIn(0f, 1f)
    }

    /** Elengedéskor: [openVelocityPxPerSec] a NYITÁS irányába mutató sebesség. */
    fun settle(openVelocityPxPerSec: Float) {
        val target = when {
            openVelocityPxPerSec > FLING_VELOCITY -> 1f
            openVelocityPxPerSec < -FLING_VELOCITY -> 0f
            openness >= 0.5f -> 1f
            else -> 0f
        }
        animateTo(target)
    }

    fun open() = animateTo(1f)

    fun close() = animateTo(0f)

    fun toggle() {
        if (openness >= 0.5f) close() else open()
    }

    private fun animateTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = openness,
                targetValue = target,
                animationSpec = tween(160),
            ) { value, _ -> openness = value }
        }
    }
}

/**
 * Gyökér-szintű fiók-húzás figyelő. A gyökér Boxra kell tenni; "Initial" menetben fut,
 * így a gyerekek (gombok, szövegmező, lista) ELŐTT látja az eseményt, de csak akkor
 * fogyasztja el, ha a mozdulat egyértelműen vízszintes fiók-húzássá vált.
 */
internal fun Modifier.drawerGestures(left: EdgeDrawerState, right: EdgeDrawerState): Modifier =
    pointerInput(left, right) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            val x0 = down.position.x
            val y0 = down.position.y

            val leftOpen = left.isOpen
            val rightOpen = right.isOpen
            val closed = !leftOpen && !rightOpen
            val inBand = y0 >= height * ZONE_TOP && y0 <= height * ZONE_BOTTOM
            val canOpenLeft = closed && inBand && x0 <= width * ZONE_WIDTH
            val canOpenRight = closed && inBand && x0 >= width * (1f - ZONE_WIDTH)
            if (closed && !canOpenLeft && !canOpenRight) return@awaitEachGesture

            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var active: EdgeDrawerState? = null
            var sign = 1f
            var lastX = x0

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) break

                if (active == null) {
                    val dx = change.position.x - x0
                    val dy = change.position.y - y0
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        val (picked, pickedSign) = when {
                            leftOpen && dx < 0f -> left to 1f
                            rightOpen && dx > 0f -> right to -1f
                            canOpenLeft && dx > 0f -> left to 1f
                            canOpenRight && dx < 0f -> right to -1f
                            else -> return@awaitEachGesture
                        }
                        picked.dragStart()
                        active = picked
                        sign = pickedSign
                        lastX = x0
                    } else if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        // Függőleges mozdulat (görgetés) - nem a fiókhoz tartozik.
                        return@awaitEachGesture
                    }
                }

                val current = active
                if (current != null) {
                    current.dragBy(sign * (change.position.x - lastX))
                    lastX = change.position.x
                    change.consume()
                }
            }

            val finished = active
            if (finished != null) {
                finished.settle(sign * tracker.calculateVelocity().x)
            }
        }
    }

/** Az oldalsó fiók: fogantyú a képernyő szélén + a fiók panel, ami a fogantyú mögül csúszik ki. */
@Composable
internal fun EdgeDrawer(
    alignEnd: Boolean,
    state: EdgeDrawerState,
    onHandleTap: () -> Unit,
    modifier: Modifier = Modifier,
    panel: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxHeight()) {
        if (!alignEnd) {
            EdgeHandle(alignEnd = false, onTap = onHandleTap)
            PanelSlot(alignEnd = false, state = state, panel = panel)
        } else {
            PanelSlot(alignEnd = true, state = state, panel = panel)
            EdgeHandle(alignEnd = true, onTap = onHandleTap)
        }
    }
}

@Composable
private fun PanelSlot(alignEnd: Boolean, state: EdgeDrawerState, panel: @Composable () -> Unit) {
    val panelWidth = DRAWER_PANEL_DP.dp
    Column(modifier = Modifier.fillMaxHeight().width(panelWidth)) {
        Spacer(Modifier.weight(HANDLE_TOP_WEIGHT))
        Box(
            modifier = Modifier.weight(HANDLE_STRIP_WEIGHT).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (state.isOpen) {
                Box(
                    modifier = Modifier.offset {
                        val shift = (1f - state.openness) * panelWidth.toPx()
                        IntOffset(x = (if (alignEnd) shift else -shift).roundToInt(), y = 0)
                    }
                ) {
                    panel()
                }
            }
        }
        Spacer(Modifier.weight(HANDLE_BOTTOM_WEIGHT))
    }
}

@Composable
private fun EdgeHandle(alignEnd: Boolean, onTap: () -> Unit) {
    val shape = if (alignEnd) {
        RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    }
    Column(modifier = Modifier.fillMaxHeight().width(HANDLE_BOX_DP.dp).zIndex(1f)) {
        Spacer(Modifier.weight(HANDLE_TOP_WEIGHT))
        Box(
            modifier = Modifier
                .weight(HANDLE_STRIP_WEIGHT)
                .fillMaxWidth()
                .clickable(onClick = onTap),
            contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .width(HANDLE_VISUAL_DP.dp)
                    .fillMaxHeight()
                    .background(HandleColor, shape)
            )
        }
        Spacer(Modifier.weight(HANDLE_BOTTOM_WEIGHT))
    }
}

/** Sötétítő réteg nyitott fióknál: koppintásra bezárja a fiókot. A tartalom és a fiókok KÖZÖTT van. */
@Composable
internal fun DrawerScrim(left: EdgeDrawerState, right: EdgeDrawerState, onDismiss: () -> Unit) {
    val anyOpen by remember { derivedStateOf { left.isOpen || right.isOpen } }
    if (anyOpen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = max(left.openness, right.openness) * 0.45f }
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
    }
}

// ---------------------------------------------------------------------------
// Fiók-tartalmak
// ---------------------------------------------------------------------------

@Composable
internal fun LeftDrawerPanel(
    onNotes: () -> Unit,
    onFunction: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
    Column(
        modifier = Modifier
            .width(DRAWER_PANEL_DP.dp)
            .wrapContentHeight()
            .background(DrawerBg, shape)
            .border(1.dp, Accent.copy(alpha = 0.3f), shape)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // A Kezdőlapra a lap alján lévő házikó-gomb visz vissza, ezért itt nincs Kezdőlap gomb.
        RailIconButton(icon = Icons.Filled.Edit, contentDescription = "Jegyzet", onClick = onNotes)
        RailTextButton(label = "F1", contentDescription = "F1", onClick = { onFunction(1) })
        RailTextButton(label = "F2", contentDescription = "F2", onClick = { onFunction(2) })
        RailTextButton(label = "F3", contentDescription = "F3", onClick = { onFunction(3) })
    }
}

@Composable
internal fun RightDrawerPanel(
    onQuickAccess: () -> Unit,
    onLogOpen: () -> Unit,
    onWebReader: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
    Column(
        modifier = Modifier
            .width(DRAWER_PANEL_DP.dp)
            .wrapContentHeight()
            .background(DrawerBg, shape)
            .border(1.dp, Accent.copy(alpha = 0.3f), shape)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RailIconButton(icon = Icons.Filled.Bolt, contentDescription = "Gyorsjelentés", onClick = onQuickAccess)
        RailIconButton(icon = Icons.Filled.FormatListBulleted, contentDescription = "Napló", onClick = onLogOpen)
        RailDivider()
        RailIconButton(icon = Icons.Filled.Public, contentDescription = "Webolvasó", onClick = onWebReader)
        RailIconButton(icon = Icons.Filled.Settings, contentDescription = "Beállítások", onClick = onSettings)
        RailIconButton(icon = Icons.Filled.Info, contentDescription = "Névjegy", onClick = onAbout)
    }
}

@Composable
private fun RailIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ButtonBg)
            .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Accent, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun RailTextButton(label: String, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ButtonBg)
            .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Accent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RailDivider() {
    Box(
        modifier = Modifier
            .width(22.dp)
            .height(1.dp)
            .background(Accent.copy(alpha = 0.3f))
    )
}

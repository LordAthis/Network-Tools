// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// A Miner's funkció ikonja: ASIC-ventilátor (keret + gyűrű + 4 lapát + agy). Egyszínű vektor, az
// Icon(tint = ...) színezi, ugyanúgy, mint a Material ikonokat a jobb fiók gombjain.
// ---------------------------------------------------------------------------

private const val FAN_FRAME = "M3,1.5 L21,1.5 A1.5,1.5 0 0,1 22.5,3 L22.5,21 A1.5,1.5 0 0,1 21,22.5 L3,22.5 A1.5,1.5 0 0,1 1.5,21 " +
    "L1.5,3 A1.5,1.5 0 0,1 3,1.5 Z M12,2.6 A9.4,9.4 0 1,0 12,21.4 A9.4,9.4 0 1,0 12,2.6 Z"

private const val FAN_BLADES = "M12.60,10.20 C11.20,8.60 10.40,5.60 12.20,4.00 C14.00,2.50 16.90,3.60 16.40,6.30 C16.00,8.30 14.40,9.70 13.70,10.90 Z " +
    "M13.80,12.60 C15.40,11.20 18.40,10.40 20.00,12.20 C21.50,14.00 20.40,16.90 17.70,16.40 C15.70,16.00 14.30,14.40 13.10,13.70 Z " +
    "M11.40,13.80 C12.80,15.40 13.60,18.40 11.80,20.00 C10.00,21.50 7.10,20.40 7.60,17.70 C8.00,15.70 9.60,14.30 10.30,13.10 Z " +
    "M10.20,11.40 C8.60,12.80 5.60,13.60 4.00,11.80 C2.50,10.00 3.60,7.10 6.30,7.60 C8.30,8.00 9.70,9.60 10.90,10.30 Z"

private const val FAN_HUB = "M12,10.3 A1.7,1.7 0 1,0 12,13.7 A1.7,1.7 0 1,0 12,10.3 Z"

/** A ventilátor-ikon (24 x 24). */
internal val FanIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MinerFan",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(pathData = addPathNodes(FAN_FRAME), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        addPath(pathData = addPathNodes(FAN_BLADES), fill = SolidColor(Color.Black))
        addPath(pathData = addPathNodes(FAN_HUB), fill = SolidColor(Color.Black))
    }.build()
}

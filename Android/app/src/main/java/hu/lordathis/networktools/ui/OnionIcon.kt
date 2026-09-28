// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// A Webolvasó Tor-módjának ikonja: hagyma (külső héj + belső rétegek). Egyszínű, az Icon(tint) színezi.

private const val ONION_OUTER = "M12,2 C11.2,3.2 10.6,4.3 10.3,5.4 C6.6,6.7 4,10 4,14 C4,18.4 7.6,22 12,22 " +
    "C16.4,22 20,18.4 20,14 C20,10 17.4,6.7 13.7,5.4 C13.4,4.3 12.8,3.2 12,2 Z " +
    "M12,7.2 C8.7,7.9 6,10.7 6,14 C6,17.3 8.7,20 12,20 C15.3,20 18,17.3 18,14 C18,10.7 15.3,7.9 12,7.2 Z"

private const val ONION_MIDDLE = "M12,8.6 C9.6,9.4 7.8,11.5 7.8,14 C7.8,16.6 9.7,18.6 12,18.6 C14.3,18.6 16.2,16.6 16.2,14 " +
    "C16.2,11.5 14.4,9.4 12,8.6 Z M12,10.3 C10.6,11 9.6,12.4 9.6,14 C9.6,15.7 10.7,17 12,17 C13.3,17 14.4,15.7 14.4,14 " +
    "C14.4,12.4 13.4,11 12,10.3 Z"

private const val ONION_CORE = "M12,11.8 C11.3,12.3 10.9,13.1 10.9,14 C10.9,14.9 11.4,15.5 12,15.5 C12.6,15.5 13.1,14.9 13.1,14 " +
    "C13.1,13.1 12.7,12.3 12,11.8 Z"

internal val OnionIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "TorOnion",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(pathData = addPathNodes(ONION_OUTER), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        addPath(pathData = addPathNodes(ONION_MIDDLE), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        addPath(pathData = addPathNodes(ONION_CORE), fill = SolidColor(Color.Black))
    }.build()
}

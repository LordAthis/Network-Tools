// Verzio: v0.3.0 - 2026-09-21
package hu.lordathis.networktools.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// A Network Tool's hálózat-ikonja (középen wifi-jeles csomópont, öt kapcsolódó csomópont, ebből
// háromon negatív wifi-jel) VEKTORKÉNT, a téma színével kifestve - ugyanaz a rajz, mint az app
// ikonja (res/drawable/ic_launcher_foreground.xml, icon/ic_launcher.svg).
// A [HomeButtonIcon] az ikont egy házikóba rajzolja: ez a lap alján lévő "vissza a Kezdőlapra" gomb.
// ---------------------------------------------------------------------------

private object IconPaths {
    const val RING = "M58.45,34.23 L73.33,45.04 M76.08,53.51 L70.39,70.99 M63.19,76.23 L44.81,76.23 M37.61,70.99 L31.92,53.51 M34.67,45.04 L49.55,34.23"
    const val SPOKES = "M54,47.5 L54,36.5 M62.08,53.37 L72.55,49.97 M59,62.88 L65.46,71.78 M49,62.88 L42.54,71.78 M45.92,53.37 L35.45,49.97"
    const val PLAIN_NODES = "M48,31 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0 M33.31,76.23 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0"
    const val WIFI_NODES = "M71.78,48.27 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0 M76.49,50.74 a1.29,1.29 0 1,0 2.57,0 a1.29,1.29 0 1,0 -2.57,0 M75.12,48.09 A3.75,3.75 0 0,1 80.43,48.09 L79.44,49.07 A2.36,2.36 0 0,0 76.11,49.07 Z M73.38,46.34 A6.22,6.22 0 0,1 82.17,46.34 L81.19,47.33 A4.82,4.82 0 0,0 74.37,47.33 Z M62.69,76.23 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0 M67.41,78.69 a1.29,1.29 0 1,0 2.57,0 a1.29,1.29 0 1,0 -2.57,0 M66.04,76.04 A3.75,3.75 0 0,1 71.35,76.04 L70.36,77.02 A2.36,2.36 0 0,0 67.03,77.02 Z M64.3,74.3 A6.22,6.22 0 0,1 73.09,74.3 L72.1,75.28 A4.82,4.82 0 0,0 65.28,75.28 Z M24.22,48.27 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0 M28.94,50.74 a1.29,1.29 0 1,0 2.57,0 a1.29,1.29 0 1,0 -2.57,0 M27.57,48.09 A3.75,3.75 0 0,1 32.88,48.09 L31.89,49.07 A2.36,2.36 0 0,0 28.56,49.07 Z M25.83,46.34 A6.22,6.22 0 0,1 34.62,46.34 L33.63,47.33 A4.82,4.82 0 0,0 26.81,47.33 Z"
    const val HUB_RING = "M45,56 a9,9 0 1,0 18,0 a9,9 0 1,0 -18,0 M46.8,56 a7.2,7.2 0 1,0 14.4,0 a7.2,7.2 0 1,0 -14.4,0"
    const val HUB_WIFI = "M52.88,59.22 a1.12,1.12 0 1,0 2.24,0 a1.12,1.12 0 1,0 -2.24,0 M51.69,56.91 A3.27,3.27 0 0,1 56.31,56.91 L55.45,57.77 A2.06,2.06 0 0,0 52.55,57.77 Z M50.17,55.39 A5.42,5.42 0 0,1 57.83,55.39 L56.97,56.25 A4.21,4.21 0 0,0 51.03,56.25 Z M48.65,53.87 A7.57,7.57 0 0,1 59.35,53.87 L58.49,54.73 A6.35,6.35 0 0,0 49.51,54.73 Z"
    const val HOUSE = "M60,6 L112,50 L100,50 L100,112 L20,112 L20,50 L8,50 Z"
}

private fun ImageVector.Builder.networkIconPaths(color: Color): ImageVector.Builder {
    addPath(
        pathData = addPathNodes(IconPaths.RING),
        stroke = SolidColor(color),
        strokeAlpha = 0.45f,
        strokeLineWidth = 1.8f,
    )
    addPath(
        pathData = addPathNodes(IconPaths.SPOKES),
        stroke = SolidColor(color),
        strokeLineWidth = 2.8f,
        strokeLineCap = StrokeCap.Round,
    )
    addPath(pathData = addPathNodes(IconPaths.PLAIN_NODES), fill = SolidColor(color))
    addPath(
        pathData = addPathNodes(IconPaths.WIFI_NODES),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(color),
    )
    addPath(
        pathData = addPathNodes(IconPaths.HUB_RING),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(color),
    )
    addPath(pathData = addPathNodes(IconPaths.HUB_WIFI), fill = SolidColor(color))
    return this
}

/** A hálózat-ikon önmagában (108 x 108-as rajzterület). */
private fun networkIconVector(color: Color): ImageVector =
    ImageVector.Builder(
        name = "NetworkIcon",
        defaultWidth = 108.dp,
        defaultHeight = 108.dp,
        viewportWidth = 108f,
        viewportHeight = 108f,
    ).networkIconPaths(color).build()

/** Házikó, benne a hálózat-ikon (120 x 120-as rajzterület). */
private fun houseNetworkVector(color: Color): ImageVector =
    ImageVector.Builder(
        name = "HouseNetworkIcon",
        defaultWidth = 120.dp,
        defaultHeight = 120.dp,
        viewportWidth = 120f,
        viewportHeight = 120f,
    ).apply {
        addPath(
            pathData = addPathNodes(IconPaths.HOUSE),
            stroke = SolidColor(color),
            strokeLineWidth = 4f,
            strokeLineJoin = StrokeJoin.Round,
        )
        // A hálózat-ikon közepe (54; 56) a ház törzsének közepére (60; 80) kerül.
        addGroup(translationX = 6f, translationY = 24f)
        networkIconPaths(color)
        clearGroup()
    }.build()

/** A nagy, álló hálózat-ikon (a Kezdőlapon). Nincs animáció. */
@Composable
internal fun NetworkIcon(modifier: Modifier = Modifier, color: Color = Accent) {
    val painter = rememberVectorPainter(remember(color) { networkIconVector(color) })
    Image(painter = painter, contentDescription = "Network Tool's", modifier = modifier)
}

/** A "vissza a Kezdőlapra" gomb ikonja: házikó, benne a hálózat-ikon. */
@Composable
internal fun HomeButtonIcon(modifier: Modifier = Modifier, color: Color = Accent) {
    val painter = rememberVectorPainter(remember(color) { houseNetworkVector(color) })
    Image(painter = painter, contentDescription = "Kezdőlap", modifier = modifier)
}

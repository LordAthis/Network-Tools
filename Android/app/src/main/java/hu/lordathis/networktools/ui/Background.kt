// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.math.sin
import kotlin.random.Random

/** Csillagos háttér: halványan pislákoló pontok. */
@Composable
internal fun StarField(modifier: Modifier = Modifier) {
    val stars = remember {
        List(55) {
            Triple(Random.nextFloat(), Random.nextFloat(), 1f + Random.nextFloat() * 2f)
        }
    }
    val starColor = StarColor
    val infiniteTransition = rememberInfiniteTransition(label = "stars")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing)
        ),
        label = "starPhase",
    )
    Canvas(modifier = modifier) {
        stars.forEachIndexed { index, (xf, yf, radius) ->
            val raw = (sin(phase + index.toFloat()) + 1f) / 2f
            val alpha = (0.15f + 0.7f * raw).coerceIn(0.1f, 1f)
            drawCircle(
                color = starColor.copy(alpha = alpha),
                radius = radius,
                center = Offset(xf * size.width, yf * size.height),
            )
        }
    }
}

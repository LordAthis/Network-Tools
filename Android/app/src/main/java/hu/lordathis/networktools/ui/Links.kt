// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit

// ---------------------------------------------------------------------------
// Kattintható címek: az appban kiírt IP-címek és URL-ek MAGUK a linkek (nincs külön gomb).
// Hogy hová nyílnak (saját Webolvasó / a telefon böngészője / kérdezzen), a Beállítások >
// Általános > Linkek kezelése dönti el - ezt a MainActivity adja át a [LocalOpenLink]-en.
// ---------------------------------------------------------------------------

/** A link-megnyitó (a MainActivity biztosítja a beállított mód szerint). */
internal val LocalOpenLink = staticCompositionLocalOf<(String) -> Unit> { {} }

private val LINK_RE = Regex("""https?://[^\s"'<>)\]]+|\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")

/** Egy megtalált szövegrészből megnyitható URL (IP -> http://ip/), vagy null, ha nem cím (pl. 255.255.255.0). */
internal fun linkTarget(match: String): String? {
    if (match.startsWith("http://") || match.startsWith("https://")) return match.trimEnd('.', ',', ';')
    val hostPart = match.substringBefore(':')
    val octets = hostPart.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it > 255 }) return null
    if (octets[0] == 0 || octets[0] == 255 || octets[0] == 127 || octets[0] >= 224) return null
    return "http://$match/"
}

/** Szöveg, amiben az IP-címek és URL-ek kattinthatók. */
@Composable
internal fun LinkifiedText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    linkColor: Color = AccentBlue,
) {
    val open = LocalOpenLink.current
    if (!LINK_RE.containsMatchIn(text)) {
        Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines, modifier = modifier)
        return
    }
    val styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val annotated: AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in LINK_RE.findAll(text)) {
            val target = linkTarget(m.value) ?: continue
            append(text.substring(last, m.range.first))
            withLink(LinkAnnotation.Clickable(tag = target, styles = styles, linkInteractionListener = { open(target) })) {
                append(m.value)
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
    Text(annotated, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines, modifier = modifier)
}

// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// SZÍNEK / SKIN - EZ A FÁJL AZ ÖSSZES SZÍN FORRÁSA.
//
// Két paletta van: [DarkPalette] (a megszokott neon-zöld/sötét kinézet) és [LightPalette]
// (világos). A Beállítások > Általános beállítások > Megjelenés választja ki, melyik érvényes:
//   SYSTEM - az Android rendszer beállítását követi (alapértelmezett)
//   DARK   - mindig sötét
//   LIGHT  - mindig világos
//
// Egy szín módosításához elég itt átírni az értéket (0xFFRRGGBB formátum;
// az első "FF" az átlátszatlanságot jelenti).
// ---------------------------------------------------------------------------

enum class Skin { SYSTEM, DARK, LIGHT }

@Immutable
internal data class AppPalette(
    // --- Háttér és felületek ---
    val bgTop: Color,          // háttér-színátmenet teteje
    val bgMid: Color,          // háttér-színátmenet közepe
    val bgBottom: Color,       // háttér-színátmenet alja
    val panelBg: Color,        // panelek háttere
    val drawerBg: Color,       // oldalsó fiókok háttere
    val buttonBg: Color,       // fiók-gombok háttere
    val handle: Color,         // fiók-fogantyú (hosszú csík a képernyő szélén)
    // --- Szöveg és kiemelés ---
    val accent: Color,         // fő kiemelő szín (zöld): gombok, keretek, címek
    val accentBlue: Color,     // másodlagos kiemelés (kék)
    val text: Color,           // fő szövegszín
    val textDim: Color,        // halvány szöveg (leírások, feliratok)
    val stripeText: Color,     // szöveg a kiemelő szín gombokon (pl. MENTÉS)
    val star: Color,           // háttér csillagok
    val warn: Color,           // figyelmeztető szín (Magas fontosság)
    val danger: Color,         // veszély-szín (Sürgős fontosság, törlés)
)

internal val DarkPalette = AppPalette(
    bgTop = Color(0xFF0D1B22),
    bgMid = Color(0xFF05070B),
    bgBottom = Color(0xFF020304),
    panelBg = Color(0xF20B0D0C),
    drawerBg = Color(0xF0090B0A),
    buttonBg = Color(0x6606140E),
    handle = Color(0xFF0F3A26),
    accent = Color(0xFF39FF8A),
    accentBlue = Color(0xFF7FC4FF),
    text = Color(0xFFEAFFF4),
    textDim = Color(0xFF6FAE90),
    stripeText = Color(0xFF03130B),
    star = Color(0xFFEAFFF4),
    warn = Color(0xFFFFB74D),
    danger = Color(0xFFFF6B6B),
)

internal val LightPalette = AppPalette(
    bgTop = Color(0xFFE6F1EC),
    bgMid = Color(0xFFF3F8F5),
    bgBottom = Color(0xFFDDEAE3),
    panelBg = Color(0xF2FFFFFF),
    drawerBg = Color(0xF0F4F9F6),
    buttonBg = Color(0xFFDCEBE3),
    handle = Color(0xFF7FBF9F),
    accent = Color(0xFF0A7A3F),
    accentBlue = Color(0xFF1F5FBF),
    text = Color(0xFF10281C),
    textDim = Color(0xFF3E6B54),
    stripeText = Color(0xFFFFFFFF),
    star = Color(0xFF7FA891),
    warn = Color(0xFFB35C00),
    danger = Color(0xFFC62828),
)

internal val LocalAppPalette = staticCompositionLocalOf { DarkPalette }

// Az egyes színek "rövid nevei" - a képernyők ezeket használják, és mindig az
// éppen érvényes skin palettájából olvasnak.
internal val BgTop: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.bgTop
internal val BgMid: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.bgMid
internal val BgBottom: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.bgBottom
internal val PanelBg: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.panelBg
internal val DrawerBg: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.drawerBg
internal val ButtonBg: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.buttonBg
internal val HandleColor: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.handle
internal val Accent: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.accent
internal val AccentBlue: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.accentBlue
internal val TextMain: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.text
internal val TextDim: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.textDim
internal val StripeTextColor: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.stripeText
internal val StarColor: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.star
internal val WarnColor: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.warn
internal val DangerColor: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.danger

/** A kiválasztott skin szerinti témát adja a tartalomnak (SYSTEM: a rendszer sötét/világos módja). */
@Composable
internal fun NetworkToolsTheme(skin: Skin, content: @Composable () -> Unit) {
    val dark = when (skin) {
        Skin.SYSTEM -> isSystemInDarkTheme()
        Skin.DARK -> true
        Skin.LIGHT -> false
    }
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
            content = content
        )
    }
}

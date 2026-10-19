package com.htmleditor.app

/**
 * Цветовая схема: 4 заданных цвета + производные (контраст, подсветка кода).
 * Роли: main — фон, soft — панели, accent — акцент, primary — фон верхних табов.
 */
class Palette(
    val schemeId: String,
    val isLight: Boolean,
    val main: Int,
    val soft: Int,
    val accent: Int,
    val primary: Int,
    /** Красивый контрастный текст/иконки на панелях (soft/main). */
    val ink: Int,
    /** Текст поверх заливки accent. */
    val onAccent: Int,
    /** Контент поверх панели табов (primary). */
    val onPrimary: Int,
    // производные цвета редактора/консоли
    val codeText: Int,
    val muted: Int,
    val hlTag: Int,
    val hlAttr: Int,
    val hlString: Int,
    val hlComment: Int,
    val searchHl: Int,
    /** Неактивный таб: onPrimary с прозрачностью. */
    val tabInactive: Int
)

object PaletteNs {
    const val CLASSIC = "classic"
    const val BLUE = "blue"
    const val GREEN = "green"
    const val WARM = "warm"

    val IDS = listOf(CLASSIC, BLUE, GREEN, WARM)

    fun C(hex: String): Int {
        val h = hex.removePrefix("#")
        return (0xFF000000 or h.toLong(16)).toInt()
    }

    fun get(schemeId: String, light: Boolean): Palette {
        val base = BASE[schemeId] ?: BASE.getValue(CLASSIC)
        val (m, s, a, p) = if (light) base.first else base.second
        val isLight = luminance(m) > 0.45

        if (schemeId == CLASSIC) {
            return if (light) {
                Palette(
                    CLASSIC, true, m, s, a, p,
                    ink = C("#3C6970"), onAccent = C("#EDE4D0"), onPrimary = C("#EDE4D0"),
                    codeText = C("#3A3632"), muted = C("#6E675E"),
                    hlTag = C("#3C6970"), hlAttr = C("#3E8FA6"),
                    hlString = C("#2F5B62"), hlComment = C("#8F857A"),
                    searchHl = C("#7FD1EC"), tabInactive = withAlpha(C("#EDE4D0"), 0xB8)
                )
            } else {
                Palette(
                    CLASSIC, false, m, s, a, p,
                    ink = C("#5FB4CB"), onAccent = C("#1E2528"), onPrimary = C("#EDE4D0"),
                    codeText = C("#E4E0D7"), muted = C("#95A1A3"),
                    hlTag = C("#7FC2D1"), hlAttr = C("#5FB4CB"),
                    hlString = C("#A7C4CC"), hlComment = C("#8A9698"),
                    searchHl = C("#2F7590"), tabInactive = withAlpha(C("#EDE4D0"), 0xB8)
                )
            }
        }

        // Схемы из наборов пользователя: контраст выводим автоматически
        val onA = bestText(a)
        val onP = bestText(p)
        val ink0 = ensureContrast(a, s, 3.0)
        val ink = ensureContrast(ink0, m, 2.6)

        val codeBase = if (isLight) C("#1A1A1A") else C("#EFEFEA")
        val codeText = mixColor(codeBase, m, 0.12)
        val muted = mixColor(codeText, m, if (isLight) 0.50 else 0.55)

        val hlAttr = ensureContrast(a, m, 4.0)
        val hlTag = ensureContrast(mixColor(p, codeText, 0.25), m, 4.0)
        val hlString = ensureContrast(mixColor(hlAttr, codeText, 0.50), m, 3.6)
        val hlComment = ensureContrast(mixColor(codeText, m, 0.52), m, 2.8)
        val searchHl = if (isLight) mixColor(a, 0xFFFFFFFF.toInt(), 0.45)
        else mixColor(a, m, 0.45)

        return Palette(
            schemeId, isLight, m, s, a, p,
            ink = ink, onAccent = onA, onPrimary = onP,
            codeText = codeText, muted = muted,
            hlTag = hlTag, hlAttr = hlAttr, hlString = hlString, hlComment = hlComment,
            searchHl = searchHl, tabInactive = withAlpha(onP, 0xB8)
        )
    }

    /** [main, soft, accent, primary] для светлого и тёмного варианта. */
    private val BASE: Map<String, Pair<IntArray, IntArray>> = mapOf(
        CLASSIC to (intArrayOf(C("#EDE4D0"), C("#D6E5E9"), C("#3E8FA6"), C("#3C6970"))
            to intArrayOf(C("#1E2528"), C("#2A3A40"), C("#5FB4CB"), C("#3C6970"))),
        BLUE to (intArrayOf(C("#D6E9F5"), C("#BFE3F5"), C("#8FCBEA"), C("#E8F4FA"))
            to intArrayOf(C("#1B3A5C"), C("#244C73"), C("#3E9BD6"), C("#BFE3F5"))),
        GREEN to (intArrayOf(C("#FFFFFF"), C("#F5FAF6"), C("#A8D5BA"), C("#7FB89A"))
            to intArrayOf(C("#1A1F1C"), C("#232B26"), C("#3E5C4B"), C("#6FA383"))),
        WARM to (intArrayOf(C("#F5EDE3"), C("#E8D9C5"), C("#E8B4B8"), C("#C9C0DC"))
            to intArrayOf(C("#3A342E"), C("#4A4038"), C("#7A5C5A"), C("#4E4A5E")))
    )

    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}

// ------------------------------------------------------------ цветовые утилиты

/** Относительная яркость (WCAG). */
fun luminance(color: Int): Double {
    fun ch(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
}

/** Контраст между двумя цветами (1..21). */
fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/** Тёмный или светлый текст лучше читается на фоне. */
fun bestText(bg: Int): Int {
    val dark = PaletteNs.C("#141A1C")
    val light = PaletteNs.C("#F2F5F0")
    return if (contrast(dark, bg) >= contrast(light, bg)) dark else light
}

/** Подтягивает цвет к нужному контрасту с фоном (смешиванием к белому/чёрному). */
fun ensureContrast(fg: Int, bg: Int, min: Double): Int {
    var c = fg
    if (contrast(c, bg) >= min) return c
    val towardWhite = luminance(bg) < 0.4
    val target = if (towardWhite) 0xFFFFFFFF.toInt() else 0xFF101418.toInt()
    var i = 0
    while (contrast(c, bg) < min && i < 14) {
        c = mixColor(c, target, 0.16)
        i++
    }
    return c
}

/** Линейное смешивание двух цветов, t = 0..1. */
fun mixColor(a: Int, b: Int, t: Double): Int {
    val ar = (a shr 16) and 0xFF
    val ag = (a shr 8) and 0xFF
    val ab = a and 0xFF
    val br = (b shr 16) and 0xFF
    val bg = (b shr 8) and 0xFF
    val bb = b and 0xFF
    val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
    val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
    val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
    return (0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or bl.toLong()).toInt()
}

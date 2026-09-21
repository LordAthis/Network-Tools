// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.crypto

import java.security.SecureRandom

/**
 * A kulcs (jelszó-karakterlánc) szabályai és generálása. Tiszta Kotlin, nincs Android-függés.
 *
 * Követelmény: legalább [MIN_LENGTH] karakter, és legalább 1 kisbetű, 1 nagybetű, 1 szám
 * és 1 speciális karakter (nem betű, nem szám, nem szóköz).
 */
object KeyPolicy {
    const val MIN_LENGTH = 18

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"       // l nélkül (félreolvasás)
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"         // I, O nélkül
    private const val DIGITS = "23456789"                        // 0, 1 nélkül
    // .env-ben és beillesztéskor is problémamentes speciális karakterek (nincs # $ " ' \ szóköz)
    private const val SPECIAL = "!@%^&*()-_=+[]{}:,.?~"

    /** A hibák listája; üres lista = a kulcs megfelel. */
    fun validate(passphrase: String): List<String> {
        val problems = ArrayList<String>()
        if (passphrase.length < MIN_LENGTH) problems.add("legalább $MIN_LENGTH karakter kell (most: ${passphrase.length})")
        if (passphrase.none { it.isLowerCase() }) problems.add("kell legalább 1 kisbetű")
        if (passphrase.none { it.isUpperCase() }) problems.add("kell legalább 1 nagybetű")
        if (passphrase.none { it.isDigit() }) problems.add("kell legalább 1 szám")
        if (passphrase.none { !it.isLetterOrDigit() && !it.isWhitespace() }) problems.add("kell legalább 1 speciális karakter")
        return problems
    }

    fun isValid(passphrase: String): Boolean = validate(passphrase).isEmpty()

    /** Véletlen, a szabályoknak megfelelő kulcs (SecureRandom). */
    fun generate(length: Int = 24, random: SecureRandom = SecureRandom()): String {
        require(length >= MIN_LENGTH) { "a kulcs legalább $MIN_LENGTH karakter" }
        val all = LOWER + UPPER + DIGITS + SPECIAL
        val chars = ArrayList<Char>()
        chars.add(LOWER[random.nextInt(LOWER.length)])
        chars.add(UPPER[random.nextInt(UPPER.length)])
        chars.add(DIGITS[random.nextInt(DIGITS.length)])
        chars.add(SPECIAL[random.nextInt(SPECIAL.length)])
        while (chars.size < length) chars.add(all[random.nextInt(all.length)])
        // Fisher-Yates keverés, hogy a kötelező karakterek helye ne legyen kiszámítható
        for (i in chars.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return chars.joinToString("")
    }
}

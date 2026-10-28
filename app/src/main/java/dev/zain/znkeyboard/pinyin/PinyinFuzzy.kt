package dev.zain.znkeyboard.pinyin

/** Common fuzzy-sound pairs. Each one folds the second spelling onto the first. */
enum class PinyinFuzzyRule(val id: String, val label: String) {
    ZZh("z_zh", "z = zh"),
    CCh("c_ch", "c = ch"),
    SSh("s_sh", "s = sh"),
    NL("n_l", "n = l"),
    AnAng("an_ang", "an = ang"),
    EnEng("en_eng", "en = eng"),
    InIng("in_ing", "in = ing"),
    ;

    companion object {
        val DEFAULTS: Set<PinyinFuzzyRule> = entries.toSet()

        fun fromIds(ids: Collection<String>): Set<PinyinFuzzyRule> =
            entries.filterTo(mutableSetOf()) { it.id in ids }
    }
}

/**
 * Folds a syllable (or the start of one) to a canonical spelling, so that every
 * spelling a fuzzy rule treats as equal maps to the same key.
 */
class PinyinFuzzy(val rules: Set<PinyinFuzzyRule>) {
    fun normalize(syllable: String): String {
        var s = syllable
        if (PinyinFuzzyRule.ZZh in rules && s.startsWith("zh")) s = "z" + s.substring(2)
        if (PinyinFuzzyRule.CCh in rules && s.startsWith("ch")) s = "c" + s.substring(2)
        if (PinyinFuzzyRule.SSh in rules && s.startsWith("sh")) s = "s" + s.substring(2)
        if (PinyinFuzzyRule.NL in rules && s.startsWith("l")) s = "n" + s.substring(1)
        // Finals only change whole syllables: a lone "a" or "e" is never a final here.
        if (s.length > 2) {
            if (PinyinFuzzyRule.AnAng in rules && s.endsWith("ang")) s = s.dropLast(1)
            if (PinyinFuzzyRule.EnEng in rules && s.endsWith("eng")) s = s.dropLast(1)
            if (PinyinFuzzyRule.InIng in rules && s.endsWith("ing")) s = s.dropLast(1)
        }
        return s
    }
}

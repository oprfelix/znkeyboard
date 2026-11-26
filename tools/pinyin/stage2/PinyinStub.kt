package dev.zain.znkeyboard.pinyin

object PinyinEngine {
    data class AiSnapshotIdentity(val session: Long, val lifecycle: Long, val generation: Long, val composing: String, val version: Long)
    data class Candidate(val text: String)
    class PreparedCandidates(val texts: List<String>) {
        val acceptsScores = true
        fun candidates(scores: FloatArray): List<Candidate> = listOf(Candidate("${texts.first()}:${scores.first()}"))
    }
}

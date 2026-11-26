package android.util

object Log {
    fun i(tag: String, message: String): Int {
        println("$tag: $message")
        return 0
    }
}

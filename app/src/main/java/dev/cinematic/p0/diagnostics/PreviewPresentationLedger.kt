package dev.cinematic.p0.diagnostics

/** 診断専用の有限な要求対応表。公開ログにキーや画像情報を出さない。 */
internal class PreviewPresentationLedger(private val capacity: Int = 128) {
    data class Request(val id: Long, val inputId: Long, val exact: Boolean)
    private var nextId = 0L
    private val pending = LinkedHashMap<Any, Long>()
    init { require(capacity > 0) }
    @Synchronized fun nextInput(): Long = ++nextId
    @Synchronized fun clear() { pending.clear() }
    @Synchronized fun bind(key: Any, inputId: Long) {
        pending[key] = inputId
        while (pending.size > capacity) pending.remove(pending.keys.first())
    }
    @Synchronized fun start(key: Any, exact: Boolean): Request? =
        pending.remove(key)?.let { Request(++nextId, it, exact) }
}

package dev.cinematic.p0.raw

/** Diagnostic CPU wall intervals. Allocation/upload splitting adds a synchronization point. */
internal class GpuWorkTiming(private val clock: () -> Long = System::nanoTime) {
    enum class Phase(val key: String) {
        CPU_ALLOCATE("cpuAllocateNs"), CPU_PACK("cpuPackNs"), CPU_UNPACK("cpuUnpackNs"),
        CPU_PREPARE("cpuInputPrepareNs"), CPU_VALIDATE("cpuValidateNs"),
        GPU_ALLOCATE("gpuAllocateNs"), UPLOAD("uploadNs"), DRAW("drawNs"),
        READBACK("readbackNs"), RELEASE("releaseNs")
    }
    enum class Count(val key: String) {
        CALLS("calls"), PASSES("passes"), CPU_ALLOCATIONS("explicitCpuAllocations"),
        CPU_BYTES("explicitCpuAllocatedBytes"), TEXTURE_ALLOCATIONS("textureAllocations"),
        TEXTURE_BYTES("textureAllocatedBytes"), UPLOAD_BYTES("uploadedBytes"), READBACK_BYTES("readbackBytes")
    }
    private val intervals = LongArray(Phase.entries.size)
    private val counts = LongArray(Count.entries.size)
    private var active: Phase? = null
    fun begin(phase: Phase): Long {
        check(active == null) { "Timing intervals must not overlap" }
        active = phase
        return clock()
    }
    fun end(phase: Phase, started: Long) {
        val elapsed = clock() - started
        check(active == phase && elapsed >= 0)
        intervals[phase.ordinal] += elapsed
        active = null
    }
    fun count(kind: Count, amount: Long = 1) {
        require(amount >= 0)
        counts[kind.ordinal] = Math.addExact(counts[kind.ordinal], amount)
    }
    fun snapshot(totalNs: Long): Snapshot {
        check(active == null)
        return Snapshot(totalNs, intervals.toList(), counts.toList())
    }
    data class Snapshot(val totalNs: Long, val intervals: List<Long>, val counts: List<Long>) {
        val otherNs: Long get() = totalNs - intervals.sum()
        init {
            require(intervals.size == Phase.entries.size && counts.size == Count.entries.size)
            require(totalNs >= 0 && intervals.all { it >= 0 } && counts.all { it >= 0 })
            require(otherNs >= 0) { "Timing exceeds the enclosing wall interval" }
        }
        fun fields(): String = "timingMode=split-allocation-v1 totalNs=$totalNs otherNs=$otherNs " +
            Phase.entries.joinToString(" ") { "${it.key}=${intervals[it.ordinal]}" } + " " +
            Count.entries.joinToString(" ") { "${it.key}=${counts[it.ordinal]}" }
        operator fun plus(other: Snapshot) = Snapshot(Math.addExact(totalNs, other.totalNs),
            intervals.zip(other.intervals) { a, b -> Math.addExact(a, b) },
            counts.zip(other.counts) { a, b -> Math.addExact(a, b) })
    }
}

internal inline fun <T> GpuWorkTiming?.measure(phase: GpuWorkTiming.Phase, block: () -> T): T {
    if (this == null) return block()
    val started = begin(phase)
    return try { block() } finally { end(phase, started) }
}

/** Counts only explicit array/direct-buffer requests here, not objects, driver memory or GC peaks. */
internal inline fun <T> GpuWorkTiming?.allocate(bytes: Long, block: () -> T): T {
    val result = measure(GpuWorkTiming.Phase.CPU_ALLOCATE, block)
    this?.count(GpuWorkTiming.Count.CPU_ALLOCATIONS)
    this?.count(GpuWorkTiming.Count.CPU_BYTES, bytes)
    return result
}

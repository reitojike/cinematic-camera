package dev.cinematic.p0.gpu.export

/** GL-local completed row work. Quantiles scans the image twice; zero strength skips B2/B3/B5.
 * Origin is deliberately not used: it restarts on every scan, including both quantile scans.
 * No GPU work, readback, source/owner references or IO. */
internal class GpuExportProgress(height: Int, strength: Double) {
    private val totalRows = height.toLong() * if (strength == 0.0) 5 else 8
    private var completedRows = 0L
    init { require(height > 0 && strength.isFinite() && strength in 0.0..1.0) }
    fun bandCompleted(rows: Int): Float {
        require(rows > 0)
        completedRows = (completedRows + rows).coerceAtMost(totalRows)
        return (completedRows.toDouble() / totalRows).toFloat()
    }
}

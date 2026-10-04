package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for `imu/imu.csv` (P8): merge order (android_monotonic),
 * last-value carry with a freshness window, gaps as empty cells,
 * missing sensors dropping their columns.
 */
class ImuCsvTest {

    private fun a(tsNs: Long, x: Float, y: Float, z: Float) =
        ImuLogger.Sample(tsNs, x, y, z)

    private fun g(tsNs: Long, p: Float, q: Float, r: Float) =
        ImuLogger.Sample(tsNs, p, q, r)

    private fun rows(text: String) = text.trim().split("\n")

    @Test
    fun `header-only file when both sensors are missing`() {
        val csv = ImuCsv.build(null, null)
        assertEquals(ImuCsv.HEADER, rows(csv)[0])
        assertEquals(1, rows(csv).size)
    }

    @Test
    fun `streams interleave in timestamp order with carry`() {
        val accel = listOf(a(0, 1f, 2f, 3f), a(20_000_000, 4f, 5f, 6f))
        val gyro = listOf(g(10_000_000, 1f, 2f, 3f), g(30_000_000, 4f, 5f, 6f))
        val out = rows(ImuCsv.build(accel, gyro))
        assertEquals(ImuCsv.HEADER, out[0])
        assertEquals(5, out.size)
        assertEquals("0,accel,1.000000,2.000000,3.000000,,,", out[1])
        // gyro row: the accel sample from 10 ms earlier is still fresh ->
        // carried in its columns (that is the point of the carry window).
        assertEquals("10000000,gyro,1.000000,2.000000,3.000000,1.000000,2.000000,3.000000", out[2])
        assertEquals("20000000,accel,4.000000,5.000000,6.000000,1.000000,2.000000,3.000000", out[3])
        assertEquals("30000000,gyro,4.000000,5.000000,6.000000,4.000000,5.000000,6.000000", out[4])
    }

    @Test
    fun `gap beyond the carry window is left empty, not carried`() {
        val accel = listOf(a(0, 1f, 0f, 0f))
        val gyro = listOf(g(10_000_000, 1f, 0f, 0f), g(100_000_000, 2f, 0f, 0f))
        val out = rows(ImuCsv.build(accel, gyro))
        // accel last fired 100 ms before the row -> not fresh -> empty.
        assertEquals("100000000,gyro,,,,2.000000,0.000000,0.000000", out[3])
    }

    @Test
    fun `missing gyro sensor drops the gyro columns`() {
        val accel = listOf(a(0, 1f, 2f, 3f))
        val out = rows(ImuCsv.build(accel, null))
        assertEquals(ImuCsv.HEADER_ACCEL_ONLY, out[0])
        assertEquals("0,accel,1.000000,2.000000,3.000000", out[1])
    }

    @Test
    fun `missing accel sensor drops the accel columns`() {
        val gyro = listOf(g(0, 1f, 2f, 3f))
        val out = rows(ImuCsv.build(null, gyro))
        assertEquals(ImuCsv.HEADER_GYRO_ONLY, out[0])
        assertEquals("0,gyro,1.000000,2.000000,3.000000", out[1])
    }

    @Test
    fun `every row has exactly the header's column count`() {
        val accel = listOf(a(0, 1f, 2f, 3f), a(1, 4f, 5f, 6f))
        val gyro = listOf(g(1, 9f, 8f, 7f))
        for (row in rows(ImuCsv.build(accel, gyro))) {
            assertEquals(8, row.split(",").size)
        }
        // and the degraded shapes stay consistent too
        for (row in rows(ImuCsv.build(accel, null))) {
            assertEquals(5, row.split(",").size)
        }
    }

    @Test
    fun `decimals use a dot regardless of any locale assumption`() {
        val out = rows(ImuCsv.build(listOf(a(0, 1f, 2.5f, -3.25f)), null))
        assertTrue(out[1].contains("2.500000"))
        assertTrue(out[1].contains("-3.250000"))
    }
}

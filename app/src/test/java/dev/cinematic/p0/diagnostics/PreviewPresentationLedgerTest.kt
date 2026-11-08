package dev.cinematic.p0.diagnostics

import org.junit.Assert.*
import org.junit.Test

class PreviewPresentationLedgerTest {
    @Test fun onlyBoundRequestsAreMeasured() {
        val ledger = PreviewPresentationLedger()
        assertNull(ledger.start("missing", false))
        val input = ledger.nextInput()
        ledger.bind("draft", input)
        val request = requireNotNull(ledger.start("draft", false))
        assertEquals(input, request.inputId)
        assertFalse(request.exact)
        assertNull(ledger.start("draft", false))
    }
    @Test fun boundedAndReplacedRequestsDoNotInheritOldInput() {
        val ledger = PreviewPresentationLedger(2)
        val old = ledger.nextInput()
        ledger.bind("old", old)
        ledger.bind("middle", old)
        val fresh = ledger.nextInput()
        ledger.bind("last", fresh)
        assertNull(ledger.start("old", true))
        ledger.bind("middle", fresh)
        assertEquals(fresh, requireNotNull(ledger.start("middle", true)).inputId)
        assertTrue(requireNotNull(ledger.start("last", true)).exact)
    }
    @Test fun independentRequestsHaveIndependentGenerations() {
        val ledger = PreviewPresentationLedger()
        val input = ledger.nextInput()
        ledger.bind("draft", input); ledger.bind("exact", input)
        assertNotEquals(ledger.start("draft", false)?.id, ledger.start("exact", true)?.id)
    }
}

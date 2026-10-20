package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpiderRequestLedgerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun requestClaimSurvivesLedgerRecreationAndDoesNotStoreRawId() {
        val file = temporaryFolder.newFile("requests.spider")
        assertTrue(file.delete())
        val requestId = "one-time-request-secret-looking-id"
        assertEquals(SpiderRequestClaimResult.CLAIMED, FileSpiderRequestLedger(file).claim(requestId))
        assertEquals(SpiderRequestClaimResult.ALREADY_CLAIMED, FileSpiderRequestLedger(file).claim(requestId))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(requestId))
    }

    @Test
    fun corruptedLedgerFailsClosedAndIsNotOverwritten() {
        val file = temporaryFolder.newFile("corrupt-requests.spider")
        val original = "do-not-replace-this-ledger".toByteArray()
        file.writeBytes(original)

        val result = FileSpiderRequestLedger(file).claim("new-request")

        assertEquals(SpiderRequestClaimResult.STORAGE_UNAVAILABLE, result)
        assertTrue(file.readBytes().contentEquals(original))
    }

    @Test
    fun malformedRequestIdsAreNotClaimed() {
        val file = temporaryFolder.newFile("requests.spider")
        assertEquals(SpiderRequestClaimResult.STORAGE_UNAVAILABLE, FileSpiderRequestLedger(file).claim(""))
        assertFalse(file.exists() && file.length() > 0)
    }
}

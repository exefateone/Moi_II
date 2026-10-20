package com.iipauk.spider

/** Signature-only stand-in so the coordinator/repository contracts can be type-checked without Android. */
object SpiderAuditRedactor {
    fun redact(value: String): String = value
}

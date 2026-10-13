package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AlertPolicyTest {
    private val hour = 3_600_000L
    private val policy = AlertPolicy()

    private fun anomaly(
        severity: Severity = Severity.WARNING,
        kind: AnomalyKind = AnomalyKind.LONG_WAKELOCK,
        uid: Int? = 10_123,
        subject: String? = "sync",
        title: String = "Long wakelock",
    ) = Anomaly(kind, severity, title, "detail", "advice", emptyList(), uid?.let(::AppUid), subject = subject)

    @Test
    fun `key ignores presentation text`() {
        assertEquals(anomaly(title = "a").key, anomaly(title = "b").key)
        assertNotEquals(anomaly(subject = "a").key, anomaly(subject = "b").key)
        assertNotEquals(anomaly(uid = 10_001).key, anomaly(uid = 10_002).key)
    }

    @Test
    fun `new findings notify and info is skipped`() {
        val d = policy.decide(listOf(anomaly(), anomaly(Severity.INFO, AnomalyKind.AMBIENT_DISPLAY, null, null)), emptyMap(), 0)
        assertEquals(listOf(anomaly().key), d.notify.map { it.key })
        assertEquals(setOf(anomaly().key), d.records.keys)
    }

    @Test
    fun `repeat is suppressed during cooldown unless severity rises`() {
        val first = policy.decide(listOf(anomaly()), emptyMap(), 0)
        assertTrue(policy.decide(listOf(anomaly()), first.records, 3 * hour).notify.isEmpty())
        assertEquals(1, policy.decide(listOf(anomaly(Severity.CRITICAL)), first.records, 3 * hour).notify.size)
        assertEquals(1, policy.decide(listOf(anomaly()), first.records, 12 * hour).notify.size)
    }

    @Test
    fun `suppressed findings keep their original timestamp`() {
        val first = policy.decide(listOf(anomaly()), emptyMap(), 0)
        val second = policy.decide(listOf(anomaly()), first.records, 6 * hour)
        assertEquals(0L, second.records.getValue(anomaly().key).notifiedAtMillis)
    }

    @Test
    fun `old records are forgotten and output is capped`() {
        val old = mapOf("gone" to AlertRecord(Severity.WARNING, 0))
        val many = (1..5).map { anomaly(subject = "tag$it") } + anomaly(Severity.CRITICAL, subject = "worst")
        val d = policy.decide(many, old, 4 * 24 * hour)
        assertEquals(3, d.notify.size)
        assertEquals("worst", d.notify.first().subject)
        assertTrue("gone" !in d.records)
    }
}

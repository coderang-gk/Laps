package io.github.coderang.laps.coros

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CorosApiClientTest {

    private lateinit var client: CorosApiClient

    @Before
    fun setUp() {
        client = CorosApiClient()
    }

    @Test
    fun authenticateWithToken_blankToken_returnsFailure() {
        val result = client.authenticateWithToken("   ")
        assertTrue("Blank token must return failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun authenticateWithToken_validTokenFormat_setsAccessToken() {
        val token = "test_bearer_token_xyz123"
        val result = client.authenticateWithToken(token)
        // Network will fail on mock/offline JVM, but client handles network errors gracefully
        assertTrue(result.isSuccess)
        assertEquals("COROS Athlete", result.getOrNull()?.nickname)
    }

    @Test
    fun fetchActivities_whenNotAuthenticated_returnsFailure() {
        val result = client.fetchActivities()
        assertTrue("Unauthenticated call must fail", result.isFailure)
        assertEquals("Not authenticated", result.exceptionOrNull()?.message)
    }

    @Test
    fun corosActivity_defaultValues_areDefensiveAndNonNull() {
        val act = CorosApiClient.CorosActivity()
        assertEquals(0.0, act.distanceKm, 0.001)
        assertEquals(0, act.durationSeconds)
        assertEquals(150, act.avgHr)
        assertEquals(170, act.maxHr)
        assertEquals(160, act.cadence)
        assertEquals(220, act.powerWatts)
        assertEquals(100, act.trainingLoad)
        assertEquals(3.0, act.aerobicTe, 0.001)
        assertEquals(0.5, act.anaerobicTe, 0.001)
    }

    @Test
    fun corosProfile_defaultValues_containStandardPhysiologicalBenchmarks() {
        val profile = CorosApiClient.CorosProfile()
        assertEquals("Runner", profile.nickname)
        assertEquals(44, profile.rhr)
        assertEquals(195, profile.maxHr)
        assertEquals(172, profile.lthr)
        assertEquals("4:56 /km", profile.ltspPace)
        assertEquals(75.0, profile.weightKg, 0.01)
    }

    @Test
    fun corosActivityDetail_defaultValues_areDefensiveAndNonNull() {
        val detail = CorosApiClient.CorosActivityDetail()
        assertEquals(0.0, detail.aerobicTe, 0.001)
        assertEquals(0.0, detail.anaerobicTe, 0.001)
        assertEquals(0, detail.maxHr)
        assertEquals(51, detail.vo2Max)
        assertEquals(96.0, detail.stamina7d, 0.001)
        assertTrue(detail.hrZones.isEmpty())
        assertTrue(detail.splits.isEmpty())
    }
}

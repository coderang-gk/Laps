package io.github.coderang.laps.coros

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CorosApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CorosApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CorosApiClient(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun authenticateWithToken_blankToken_returnsFailure() {
        val result = client.authenticateWithToken("   ")
        assertTrue("Blank token must return failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun authenticateWithToken_validTokenAndSuccessResponse_returnsSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"result": "0000", "message": "OK"}"""))
        val result = client.authenticateWithToken("valid_bearer_token")
        assertTrue(result.isSuccess)
        assertEquals("COROS Athlete", result.getOrNull()?.nickname)
    }

    @Test
    fun authenticateWithToken_invalidTokenOrExpired_returnsFailure() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"result": "1001", "message": "Unauthorized"}"""))
        val result = client.authenticateWithToken("expired_token")
        assertTrue("Expired token must fail", result.isFailure)
    }

    @Test
    fun fetchActivities_whenNotAuthenticated_returnsFailure() {
        val unauthClient = CorosApiClient(server.url("/").toString())
        val result = unauthClient.fetchActivities()
        assertTrue("Unauthenticated call must fail", result.isFailure)
        assertEquals("Not authenticated", result.exceptionOrNull()?.message)
    }

    @Test
    fun fetchActivities_whenErrorCodeReturned_returnsFailure() {
        client.setAccessToken("valid_token")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"result": "1002", "message": "Token expired"}"""))
        val result = client.fetchActivities()
        assertTrue("Result code 1002 must return failure instead of empty list", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("1002") == true)
    }

    @Test
    fun fetchActivities_whenSuccess_parsesActivitiesAccurately() {
        client.setAccessToken("valid_token")
        val json = """
            {
                "result": "0000",
                "data": {
                    "dataList": [
                        {
                            "labelId": "run123",
                            "name": "Morning Easy Run",
                            "distance": 8500,
                            "totalTime": 3000,
                            "startTime": 1700000000,
                            "date": 20261020,
                            "avgHr": 145,
                            "maxHr": 162,
                            "avgCadence": 168,
                            "avgPower": 230,
                            "trainingLoad": 120,
                            "aerobicEffect": 3.2,
                            "anaerobicEffect": 0.4,
                            "ascent": 35,
                            "calorie": 550
                        }
                    ]
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))
        val result = client.fetchActivities()
        assertTrue(result.isSuccess)
        val list = result.getOrNull() ?: emptyList()
        assertEquals(1, list.size)
        val run = list.first()
        assertEquals("Morning Easy Run", run.name)
        assertEquals(8.5, run.distanceKm, 0.01)
        assertEquals(145, run.avgHr)
        assertEquals(162, run.maxHr)
        assertEquals(120, run.trainingLoad)
    }

    @Test
    fun fetchActivityDetail_handlesMalformedOrEmptySplitsDefensively() {
        client.setAccessToken("valid_token")
        val json = """
            {
                "result": "0000",
                "data": {
                    "summary": {
                        "aerobicEffect": 3.5,
                        "anaerobicEffect": 0.8,
                        "maxHr": 175,
                        "currentVo2Max": 54,
                        "calories": 620
                    },
                    "zoneList": [],
                    "lapList": null
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))
        val result = client.fetchActivityDetail("run123")
        assertTrue("Malformed splits or empty zones must not crash detail query", result.isSuccess)
        val detail = result.getOrNull()
        assertNotNull(detail)
        assertEquals(3.5, detail!!.aerobicTe, 0.01)
        assertEquals(175, detail.maxHr)
        assertEquals(54, detail.vo2Max)
        assertTrue(detail.splits.isEmpty())
        assertTrue(detail.hrZones.isEmpty())
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

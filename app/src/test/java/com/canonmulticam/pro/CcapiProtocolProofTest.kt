package com.canonmulticam.pro

import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.transport.CcapiCameraDriver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CcapiProtocolProofTest {

    private lateinit var server: MockWebServer
    private val capturedPutBodies = CopyOnWriteArrayList<Pair<String, String>>()
    private var storageJsonResponse = """
        {
          "storagelist": [
            { "name": "card1", "accesscapability": "readwrite", "remainingtime": 3600 },
            { "name": "card2", "accesscapability": "readwrite", "remainingtime": 1800 },
            { "name": "card3", "accesscapability": "readonly", "remainingtime": 9000 }
          ]
        }
    """.trimIndent()
    private var batteryJsonResponse = """{ "level": "low", "value": 18 }"""

    @Before
    fun setUp() {
        capturedPutBodies.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                val method = request.method ?: "GET"

                if (method == "PUT") {
                    capturedPutBodies.add(path to request.body.readUtf8())
                    return MockResponse().setResponseCode(200).setBody("{}")
                }

                return when {
                    path == "/ccapi" -> MockResponse().setBody(
                        """
                        {
                          "ver100": [
                            { "path": "/ccapi/ver100/deviceinformation" },
                            { "path": "/ccapi/ver100/devicestatus/battery" },
                            { "path": "/ccapi/ver100/devicestatus/storage" }
                          ],
                          "ver110": [
                            { "path": "/ccapi/ver110/shooting/settings/av" },
                            { "path": "/ccapi/ver110/shooting/settings/tv" },
                            { "path": "/ccapi/ver110/shooting/settings/iso" },
                            { "path": "/ccapi/ver110/shooting/settings/colortemperature" }
                          ]
                        }
                        """.trimIndent()
                    )
                    path.endsWith("/deviceinformation") -> MockResponse().setBody(
                        """{ "productname": "Canon EOS R6 Mark II", "serialnumber": "SN-998877" }"""
                    )
                    path.endsWith("/shooting/settings/av") -> MockResponse().setBody(
                        """{ "value": "f4.0", "ability": ["f4.0", "f5.6", "f8.0"] }"""
                    )
                    path.endsWith("/shooting/settings/tv") -> MockResponse().setBody(
                        """{ "value": "1/50", "ability": ["1/50", "1/100", "1/200"] }"""
                    )
                    path.endsWith("/shooting/settings/iso") -> MockResponse().setBody(
                        """{ "value": "400" }"""
                    )
                    path.endsWith("/shooting/settings/colortemperature") -> MockResponse().setBody(
                        """{ "value": 5600 }"""
                    )
                    path.endsWith("/devicestatus/storage") -> MockResponse().setBody(storageJsonResponse)
                    path.endsWith("/devicestatus/battery") -> MockResponse().setBody(batteryJsonResponse)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `prove handshake resolves ver110 endpoints and ISO compensates unsupported f2_8 aperture`() =
        runBlocking {
            val driver = CcapiCameraDriver(server.hostName, server.port)

            val info = driver.handshake().getOrThrow()
            assertEquals("Canon EOS R6 Mark II", info.modelName)
            assertEquals("SN-998877", info.uuid)
            assertFalse("f2.8 must not be in supported list", info.supportedApertures.contains("f2.8"))

            val masterTarget = ExposureSettings(
                aperture = "f2.8",
                shutterSpeed = "1/50",
                iso = 400,
                kelvin = 5200
            )
            val applyRes = driver.applyExposure(
                masterTarget, info.supportedApertures, info.supportedShutters
            )
            assertTrue(applyRes.isSuccess)

            val isoPut = capturedPutBodies.firstOrNull {
                it.first == "/ccapi/ver110/shooting/settings/iso"
            }
            val nonNullIsoPut = requireNotNull(isoPut) {
                "ISO must be updated via discovered ver110 path"
            }
            val sentIso = JSONObject(nonNullIsoPut.second).getString("value").toInt()
            assertEquals("ISO must be compensated from 400 to 816", 816, sentIso)

            val kelvinPut = capturedPutBodies.firstOrNull {
                it.first == "/ccapi/ver110/shooting/settings/colortemperature"
            }
            val nonNullKelvinPut = requireNotNull(kelvinPut) { "Kelvin must be updated" }
            assertEquals(5200, JSONObject(nonNullKelvinPut.second).getInt("value"))

            driver.close()
        }

    @Test
    fun `prove remaining SD card minutes sum only readwrite slots and battery low flag triggers at 18 percent`() =
        runBlocking {
            val driver = CcapiCameraDriver(server.hostName, server.port)
            driver.handshake().getOrThrow()

            val mins = driver.getRemainingRecordMinutes().getOrThrow()
            assertEquals("Must sum only readwrite cards (60m + 30m = 90m)", 90, mins)

            storageJsonResponse = """{ "storagelist": [] }"""
            assertNull(
                "Empty storage list must return null minutes",
                driver.getRemainingRecordMinutes().getOrThrow()
            )

            val (batLabel, isLow) = driver.getBatteryStatus().getOrThrow()
            assertEquals("18%", batLabel)
            assertTrue("18% battery must trigger low-battery alert", isLow)

            driver.close()
        }
}

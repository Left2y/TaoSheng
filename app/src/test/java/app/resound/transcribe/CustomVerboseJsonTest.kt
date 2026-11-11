package app.resound.transcribe

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class CustomVerboseJsonTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun audio(separateSpeakers: Boolean = true) = AsrAudio(File("unused.ogg"), "ogg", 12_480, separateSpeakers)
    private val response = """{"text":"明天几点开会？下午两点。","segments":[
        {"start":0.0,"end":3.2,"text":"明天几点开会？","speaker":"spk0"},
        {"start":3.4,"end":5.1,"text":"下午两点。","speaker":"spk1"}]}"""
    private val expected = listOf(
        AsrSentence(0, 3200, "明天几点开会？", "custom:spk0"),
        AsrSentence(3400, 5100, "下午两点。", "custom:spk1"),
    )

    @Test fun segmentsBecomeSentencesWithMillisecondsAndNamespacedSpeakers() {
        assertEquals(expected, transcriptionSentences(JSONObject(response), audio()))
    }

    @Test fun speakerTagsRespectSeparateSpeakersAndOptionalSpeaker() {
        assertEquals(expected.map { it.copy(speakerTag = null) }, transcriptionSentences(JSONObject(response), audio(false)))
        val json = JSONObject("""{"segments":[
            {"start":0,"end":1,"text":"甲","speaker":2},
            {"start":1,"end":2,"text":"乙","speaker":null},
            {"start":2,"end":3,"text":"丙"}]}""")
        assertEquals(listOf("custom:2", null, null), transcriptionSentences(json, audio()).map { it.speakerTag })
    }

    @Test fun timestampsAreTruncatedToMillisecondsAndClampedToAudio() {
        val json = JSONObject("""{"segments":[
            {"start":-0.5,"end":1.2349,"text":" 开头 "},
            {"start":3.4009,"end":20.0,"text":"结尾"}]}""")
        assertEquals(listOf(AsrSentence(0, 1234, "开头", null), AsrSentence(3400, 12_480, "结尾", null)),
            transcriptionSentences(json, audio()))
    }

    @Test fun invalidSegmentsAreDroppedWithoutLosingValidOnes() {
        val json = JSONObject("""{"text":"不能盖过有效段","segments":[
            null,{"start":0,"end":1,"text":" "},{"start":0,"end":1,"text":null},
            {"start":0,"text":"缺结束"},{"end":1,"text":"缺开始"},
            {"start":"NaN","end":1,"text":"非有限数"},
            {"start":0,"end":"Infinity","text":"无限"},
            {"start":0,"end":"坏时间","text":"非数字"},
            {"start":20,"end":15,"text":"截断前已倒序"},
            {"start":1.0009,"end":1.0001,"text":"取整前已倒序"},
            {"start":1,"end":2,"text":"有效"}]}""")
        assertEquals(listOf(AsrSentence(1000, 2000, "有效", null)), transcriptionSentences(json, audio()))
    }

    @Test fun emptyOrEntirelyInvalidSegmentsFallBackToTopLevelText() {
        for (segments in listOf("[]", "null", "[null,{}, {\"start\":2,\"end\":1,\"text\":\"倒序\"}]")) {
            val json = JSONObject("""{"text":" 回退正文 ","segments":$segments}""")
            assertEquals(listOf(AsrSentence(0, 12_480, "回退正文", null)), transcriptionSentences(json, audio()))
        }
    }

    @Test fun plainTextAndBlankTextKeepExistingBehavior() {
        assertEquals(listOf(AsrSentence(0, 12_480, "你好", null)),
            transcriptionSentences(JSONObject("""{"text":" 你好 "}"""), audio()))
        assertTrue(transcriptionSentences(JSONObject("""{"text":" "}"""), audio()).isEmpty())
    }

    @Test fun minimaxErrorCodesKeepExistingFixedMessages() {
        for ((code, error) in listOf(1004 to AsrError.Auth, 1000 to AsrError.Protocol)) {
            val json = JSONObject("""{"base_resp":{"status_code":$code,"status_msg":"服务端原文"}}""")
            val e = assertThrows(AsrException::class.java) { transcriptionSentences(json, audio()) }
            assertEquals(error, e.type)
            assertFalse(e.detail.contains("服务端原文"))
        }
        assertEquals(AsrError.Protocol,
            assertThrows(AsrException::class.java) { transcriptionSentences(JSONObject("{}"), audio()) }.type)
    }

    @Test fun defaultRequestMatchesLegacyBodyAndStillParsesSegmentsInBothModes() = runBlocking {
        MockWebServer().use { server ->
            val file = File.createTempFile("verbose-json", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2)) }
            try {
                for (mode in CustomEndpointMode.entries) {
                    val settings = AndroidTranscribeSettings(context)
                    assertFalse(settings.customVerboseJson.value)
                    settings.saveCustom(server.url(if (mode == CustomEndpointMode.Auto) "/" else "/speech").toString(), "paraformer-zh", mode)
                    server.enqueue(MockResponse().setBody(response))
                    val result = CustomAsrProvider(settings) { listOf("涛声") }.recognize(audio().copy(file = file), "") {}
                    assertEquals(expected, result.sentences)
                    assertEquals(12_480L, result.durationMillis)
                    val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                    assertEquals(if (mode == CustomEndpointMode.Auto) "/v1/audio/transcriptions" else "/speech", request.path)
                    assertNull(request.getHeader("Authorization"))
                    // 用相同 boundary 重建改动前的请求，逐字节比较含文件在内的整个 body。
                    val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
                    val legacy = MultipartBody.Builder(boundary).setType(MultipartBody.FORM)
                        .addFormDataPart("model", "paraformer-zh")
                        .addFormDataPart("file", file.name, file.asRequestBody("audio/ogg".toMediaType()))
                        .addFormDataPart("response_format", "json").apply {
                            if (mode == CustomEndpointMode.Auto) addFormDataPart("prompt", "可能出现的词：涛声")
                        }.build()
                    val expectedBody = Buffer().also { legacy.writeTo(it) }
                    assertArrayEquals(expectedBody.readByteArray(), request.body.readByteArray())
                }
            } finally { file.delete() }
        }
    }

    @Test fun enabledRequestSendsVerboseJsonAndSegmentGranularityInBothModes() = runBlocking {
        MockWebServer().use { server ->
            val file = File.createTempFile("verbose-json", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2)) }
            try {
                for (mode in CustomEndpointMode.entries) {
                    val settings = AndroidTranscribeSettings(context)
                    settings.saveCustom(server.url(if (mode == CustomEndpointMode.Auto) "/" else "/speech").toString(), "paraformer-zh", mode)
                    settings.setCustomVerboseJson(true)
                    server.enqueue(MockResponse().setBody("""{"text":"旧服务仍可回退"}"""))
                    val result = CustomAsrProvider(settings).recognize(audio().copy(file = file), "test-key") {}
                    assertEquals(listOf(AsrSentence(0, 12_480, "旧服务仍可回退", null)), result.sentences)
                    val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                    assertEquals("Bearer test-key", request.getHeader("Authorization"))
                    val body = request.body.readUtf8()
                    val fields = Regex("Content-Disposition: form-data; name=\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
                    assertEquals(listOf("model", "file", "response_format", "timestamp_granularities[]"), fields)
                    assertTrue(body.contains("name=\"response_format\"\r\nContent-Length: 12\r\n\r\nverbose_json\r\n"))
                    assertTrue(body.contains("name=\"timestamp_granularities[]\"\r\nContent-Length: 7\r\n\r\nsegment\r\n"))
                }
            } finally { file.delete() }
        }
    }

    @Test fun verboseJsonDefaultsOffAndPersistsBothValuesAcrossRestart() {
        val settings = AndroidTranscribeSettings(context)
        assertFalse(settings.customVerboseJson.value)
        var changed = 0
        settings.onChanged = { changed++ }
        settings.setCustomVerboseJson(true)
        assertTrue(settings.customVerboseJson.value)
        val reloaded = AndroidTranscribeSettings(context)
        assertTrue(reloaded.customVerboseJson.value)
        assertFalse(reloaded.customPrompt.value)
        reloaded.setCustomVerboseJson(false)
        assertFalse(AndroidTranscribeSettings(context).customVerboseJson.value)
        assertEquals(1, changed)
    }
}

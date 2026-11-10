package studio.stacktrace.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import studio.stacktrace.*
import kotlin.test.*

class StudioApiClientTest {
    private val jsonHeaders=headersOf(HttpHeaders.ContentType,"application/json")
    @Test fun sendsBearerTokenAndDecodesProjects() = runTest {
        val engine=MockEngine { request ->
            assertEquals("Bearer secret",request.headers[HttpHeaders.Authorization])
            assertEquals("/api/v1/projects",request.url.encodedPath)
            respond("""[{"id":"p","name":"Example","applicationId":"com.example.app","createdAt":"2026-01-01T00:00:00Z"}]""",headers=jsonHeaders)
        }
        val api=StudioApiClient(HttpClient(engine),"https://studio.example/") {"secret"}
        try { assertEquals("Example",api.projects().single().name) } finally {api.close()}
    }
    @Test fun exposesStructuredAuthenticationErrors() = runTest {
        val api=StudioApiClient(HttpClient(MockEngine { respond("""{"code":"UNAUTHORIZED","message":"Valid bearer token required"}""",HttpStatusCode.Unauthorized,jsonHeaders) }),"https://studio.example") {"bad"}
        try {
            val failure=assertFailsWith<StudioApiException> {api.projects()}
            assertEquals(401,failure.status);assertEquals("UNAUTHORIZED",failure.code)
        } finally {api.close()}
    }
    @Test fun handlesNoContentDeletionAndDevelopmentWithoutToken() = runTest {
        val api=StudioApiClient(HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Delete,request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("",HttpStatusCode.NoContent)
        }),"https://studio.example") {""}
        try {api.deleteMapping("build")} finally {api.close()}
    }
    @Test fun preservesBatchErrorResults() = runTest {
        val response="""{"results":[{"success":false,"original":"","translated":null,"status":"ERROR","build":{"id":"b","projectId":"p","versionName":"1","versionCode":1,"variant":"release","createdAt":"now"},"errorCode":"EMPTY_TRACE","message":"Stack trace is empty"}]}"""
        val api=StudioApiClient(HttpClient(MockEngine { respond(response,headers=jsonHeaders) }),"https://studio.example") {"secret"}
        try {
            val result=api.retrace(BatchRetraceRequest(listOf(""),buildId="b")).single()
            assertFalse(result.success);assertEquals("",result.original);assertEquals("EMPTY_TRACE",result.errorCode)
        } finally {api.close()}
    }
}

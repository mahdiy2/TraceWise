package studio.stacktrace.data

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.stacktrace.*

class StudioApiException(val status: Int, val code: String, message: String): RuntimeException(message)

/** Platform-independent HTTP operations. Browser-native multipart upload avoids buffering APKs. */
class StudioApiClient(private val client: HttpClient, baseUrl: String, private val token: () -> String) {
    private val base = baseUrl.trimEnd('/') + "/api/v1"
    private val json = Json { encodeDefaults=true; ignoreUnknownKeys=true }
    private suspend inline fun <reified T> request(path: String, method: HttpMethod=HttpMethod.Get, body: String?=null): T {
        val response = client.request(base+path) {
            this.method=method
            if(token().isNotBlank()) bearerAuth(token())
            if(body!=null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = response.bodyAsText()
        if(!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<ApiError>(text) }.getOrNull()
            throw StudioApiException(response.status.value,error?.code ?: "HTTP_ERROR",error?.message ?: "Request failed (${response.status.value})")
        }
        return json.decodeFromString<T>(text)
    }
    private suspend fun delete(path: String) {
        val response=client.delete(base+path) { if(token().isNotBlank()) bearerAuth(token()) }
        if(!response.status.isSuccess()) {
            val error=runCatching { json.decodeFromString<ApiError>(response.bodyAsText()) }.getOrNull()
            throw StudioApiException(response.status.value,error?.code ?: "HTTP_ERROR",error?.message ?: "Delete failed")
        }
    }
    suspend fun projects(): List<ApplicationProject> = request("/projects")
    suspend fun createProject(value: ProjectRequest): ApplicationProject = request("/projects",HttpMethod.Post,json.encodeToString(value))
    suspend fun updateProject(id: String,value: ProjectRequest): ApplicationProject = request("/projects/$id",HttpMethod.Put,json.encodeToString(value))
    suspend fun deleteProject(id: String) = delete("/projects/$id")
    suspend fun builds(projectId: String): List<ApplicationBuild> = request("/projects/$projectId/builds")
    suspend fun createBuild(projectId: String,value: BuildRequest): ApplicationBuild = request("/projects/$projectId/builds",HttpMethod.Post,json.encodeToString(value))
    suspend fun deleteBuild(id: String) = delete("/builds/$id")
    suspend fun deleteMapping(id: String) = delete("/builds/$id/mapping")
    suspend fun retrace(value: BatchRetraceRequest): List<RetraceResponse> = request<BatchRetraceResponse>("/retrace/batch",HttpMethod.Post,json.encodeToString(value)).results
    fun close() = client.close()
}

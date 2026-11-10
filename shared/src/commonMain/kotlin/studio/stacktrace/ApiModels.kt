package studio.stacktrace

import kotlinx.serialization.Serializable

@Serializable data class ProjectRequest(val name: String, val applicationId: String, val description: String? = null)
@Serializable data class BuildRequest(val versionName: String, val versionCode: Long, val variant: String = "release")
@Serializable data class RetraceRequest(
    val stackTrace: String,
    val buildId: String? = null,
    val applicationId: String? = null,
    val projectId: String? = null,
    val versionCode: Long? = null,
    val variant: String? = null,
)
@Serializable data class BatchRetraceRequest(
    val stackTraces: List<String>,
    val buildId: String? = null,
    val applicationId: String? = null,
    val projectId: String? = null,
    val versionCode: Long? = null,
    val variant: String? = null,
)
@Serializable data class RetraceResponse(
    val success: Boolean,
    val original: String,
    val translated: String?,
    val status: RetraceStatus,
    val build: ApplicationBuild,
    val errorCode: String? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)
@Serializable data class BatchRetraceResponse(val results: List<RetraceResponse>)
@Serializable data class ApiError(val code: String, val message: String)

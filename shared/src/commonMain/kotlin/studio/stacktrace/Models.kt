package studio.stacktrace

import kotlinx.serialization.Serializable

/** Metadata shared by offline desktop and HTTP clients. Artifacts remain private. */
@Serializable
data class ApplicationProject(
    val id: String,
    val name: String,
    val applicationId: String,
    val description: String? = null,
    val createdAt: String,
)

@Serializable
data class ApplicationBuild(
    val id: String,
    val projectId: String,
    val versionName: String,
    val versionCode: Long,
    val variant: String,
    val mappingSha256: String? = null,
    val apkSha256: String? = null,
    val createdAt: String,
)

@Serializable
enum class RetraceStatus { SUCCESS, ERROR }

@Serializable
data class RetraceResult(
    val original: String,
    val translated: String?,
    val status: RetraceStatus,
    val errorCode: String? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class RecentRetrace(
    val id: String,
    val projectName: String,
    val versionName: String,
    val versionCode: Long,
    val traceCount: Int,
    val successCount: Int,
    val createdAt: String,
)

@Serializable
data class DashboardSummary(
    val projectCount: Int,
    val buildCount: Int,
    val recentBuilds: List<ApplicationBuild>,
    val recentRetraces: List<RecentRetrace>,
)

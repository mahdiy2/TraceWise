package studio.stacktrace.storage

import studio.stacktrace.*
import java.nio.file.Path

/** JVM storage boundary shared by offline desktop and HTTP services. */
interface WorkspaceRepository {
    fun projects(): List<ApplicationProject>
    fun createProject(name: String,applicationId: String,description: String?=null): ApplicationProject
    fun updateProject(id: String,name: String,description: String?)
    fun builds(projectId: String): List<ApplicationBuild>
    fun getBuild(id: String): ApplicationBuild
    fun createBuild(projectId: String,versionName: String,versionCode: Long,variant: String="release"): ApplicationBuild
    fun dashboard(): DashboardSummary
    fun recordRetrace(build: ApplicationBuild,results: List<RetraceResult>)
    fun upload(buildId: String,source: Path,apk: Boolean=false): ApplicationBuild
    fun <T> withMapping(build: ApplicationBuild,block: (Path)->T): T
    fun deleteMapping(buildId: String)
    fun deleteBuild(id: String)
    fun deleteProject(id: String)
}

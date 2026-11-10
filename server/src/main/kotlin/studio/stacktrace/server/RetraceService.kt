package studio.stacktrace.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import studio.stacktrace.*
import studio.stacktrace.storage.WorkspaceRepository
import studio.stacktrace.retrace.R8RetraceEngine

class RetraceService(private val store: WorkspaceRepository,private val engine: R8RetraceEngine,concurrency: Int) {
    private val permits = Semaphore(concurrency)
    suspend fun run(buildId: String?, applicationId: String?, projectId: String?, versionCode: Long?,variant: String?,traces: List<String>): List<RetraceResponse> {
        require(listOfNotNull(buildId,applicationId,projectId).size==1) { "Specify exactly one of buildId, applicationId or projectId" }
        buildId?.let {validateId(it)}
        projectId?.let {validateId(it)}
        applicationId?.let {require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {"Invalid application ID"}}
        require(traces.isNotEmpty() && traces.size<=100) { "Submit between 1 and 100 traces" }
        require(traces.sumOf { it.encodeToByteArray().size.toLong() }<=10L*1024*1024) { "Batch exceeds 10 MiB" }
        require(variant==null || variant in listOf("debug","release")) { "Invalid variant" }
        val build = disk {
            if(buildId!=null) {
                require(versionCode==null && variant==null) { "buildId cannot be combined with version selectors" }
                findBuild(store,buildId)
            } else {
                require(versionCode!=null && versionCode>=0) { "A nonnegative versionCode is required" }
                val project = store.projects().singleOrNull {
                    if(projectId!=null) it.id==projectId else it.applicationId==applicationId
                } ?: missing("Application")
                val matches = store.builds(project.id).filter { it.versionCode==versionCode && (variant==null || it.variant==variant) }
                if(matches.isEmpty()) missing("Build")
                if(matches.size>1) throw ApiProblem(HttpStatusCode.Conflict,"AMBIGUOUS_BUILD","Specify variant to identify the build")
                matches.single()
            }
        }
        if(build.mappingSha256==null) throw ApiProblem(HttpStatusCode.Conflict,"MAPPING_MISSING","Upload a mapping for the selected build")
        val results = permits.withPermit { disk { try {
            store.withMapping(build) { engine.retraceBatch(it,traces) }.also { store.recordRetrace(build,it) }
        } catch(_: java.nio.file.NoSuchFileException) {
            throw ApiProblem(HttpStatusCode.Conflict,"MAPPING_MISSING","Mapping was removed; upload it again or retry with the current build")
        } } }
        return results.map { r -> RetraceResponse(r.status==RetraceStatus.SUCCESS,r.original,r.translated,r.status,build,r.errorCode,r.message,r.warnings) }
    }
}

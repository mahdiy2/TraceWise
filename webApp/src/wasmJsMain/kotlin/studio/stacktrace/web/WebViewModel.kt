package studio.stacktrace.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import studio.stacktrace.*
import studio.stacktrace.data.StudioApiClient

// Credentials live only in memory and are never persisted to browser storage.
data class WebState(
    val connected: Boolean=false,
    val projects: List<ApplicationProject> = emptyList(),
    val builds: List<ApplicationBuild> = emptyList(),
    val project: ApplicationProject?=null,
    val build: ApplicationBuild?=null,
    val results: List<RetraceResponse> = emptyList(),
    val busy: Boolean=false,
    val error: String?=null,
    val message: String?=null,
)
class WebViewModel {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var token=""
    private val api=StudioApiClient(HttpClient(Js),browserOrigin()) { token }
    private val mutable=MutableStateFlow(WebState())
    val state=mutable.asStateFlow()
    fun action(block: suspend () -> Unit) {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,error=null,message=null) }
        scope.launch {
            try { block() }
            catch(e: Exception) {
                if(e is CancellationException) throw e
                mutable.update { it.copy(error=e.message ?: "Operation failed. Check your connection and input.") }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    private suspend fun refresh(projectId: String?=mutable.value.project?.id,buildId: String?=mutable.value.build?.id) {
        val projects=api.projects()
        val project=projects.find { it.id==projectId } ?: projects.firstOrNull()
        val builds=project?.let { api.builds(it.id) } ?: emptyList()
        mutable.update { it.copy(connected=true,projects=projects,project=project,builds=builds,build=builds.find { b -> b.id==buildId } ?: builds.firstOrNull(),results=emptyList()) }
    }
    fun connect(value: String)=action { token=value; refresh() }
    fun disconnect() { if(!mutable.value.busy) { token=""; mutable.value=WebState() } }
    fun selectProject(id: String)=action { refresh(id,null) }
    fun selectBuild(id: String) { if(!mutable.value.busy) mutable.update { it.copy(build=it.builds.find { b -> b.id==id },results=emptyList()) } }
    fun createProject(name: String,applicationId: String)=action {
        val p=api.createProject(ProjectRequest(name,applicationId)); refresh(p.id,null)
    }
    fun updateProject(name: String,description: String)=action {
        val p=mutable.value.project ?: error("Select a project")
        api.updateProject(p.id,ProjectRequest(name,p.applicationId,description.ifBlank { null })); refresh()
    }
    fun deleteProject()=action { mutable.value.project?.let { api.deleteProject(it.id) }; refresh(null,null) }
    fun createBuild(version: String,code: String,variant: String)=action {
        val p=mutable.value.project ?: error("Select a project")
        val number=code.toLongOrNull() ?: error("Version code must be an integer")
        val b=api.createBuild(p.id,BuildRequest(version,number,variant)); refresh(p.id,b.id)
    }
    fun deleteBuild()=action { mutable.value.build?.let { api.deleteBuild(it.id) }; refresh(buildId=null) }
    fun upload(apk: Boolean)=action {
        val b=mutable.value.build ?: error("Select a build")
        if(uploadArtifact(b.id,token,apk)) { refresh(); mutable.update { it.copy(message="Artifact uploaded") } }
    }
    fun deleteMapping()=action { mutable.value.build?.let { api.deleteMapping(it.id) }; refresh() }
    fun clearResults() { mutable.update { it.copy(results=emptyList(),error=null) } }
    fun retrace(traces: List<String>)=action {
        val b=mutable.value.build ?: error("Select a build")
        mutable.update { it.copy(results=emptyList()) }
        val results=api.retrace(BatchRetraceRequest(traces,buildId=b.id))
        mutable.update { it.copy(results=results,message="Processed ${results.size} trace(s)") }
    }
    fun close() { scope.cancel(); api.close() }
}

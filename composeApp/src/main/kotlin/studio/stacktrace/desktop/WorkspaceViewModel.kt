package studio.stacktrace.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import studio.stacktrace.*
import studio.stacktrace.storage.WorkspaceRepository
import studio.stacktrace.retrace.R8RetraceEngine
import java.nio.file.Path

data class WorkspaceState(
    val projects: List<ApplicationProject> = emptyList(),
    val builds: List<ApplicationBuild> = emptyList(),
    val project: ApplicationProject? = null,
    val build: ApplicationBuild? = null,
    val results: List<RetraceResult> = emptyList(),
    val dashboard: DashboardSummary = DashboardSummary(0,0,emptyList(),emptyList()),
    val busy: Boolean = false,
    val error: String? = null,
)
class WorkspaceViewModel(
    private val store: WorkspaceRepository,
    private val engine: R8RetraceEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    init { action { refresh() } }
    private fun action(block: suspend () -> Unit) {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,error=null) }
        scope.launch {
            try { block() }
            catch(e: Exception) {
                if(e is CancellationException) throw e
                mutable.update { it.copy(error=when(e) {
                    is java.nio.file.NoSuchFileException -> "Mapping file is missing. Upload it again."
                    is IllegalArgumentException -> e.message ?: "Invalid input"
                    else -> "Operation failed. Check the input, permissions, and duplicate project/build values."
                }) }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    private suspend fun refresh(projectId: String? = mutable.value.project?.id, buildId: String? = mutable.value.build?.id) {
        val projects = withContext(ioDispatcher) { store.projects() }
        val project = projects.find { it.id==projectId } ?: projects.firstOrNull()
        val builds = withContext(ioDispatcher) { project?.let { store.builds(it.id) } ?: emptyList() }
        val dashboard = withContext(ioDispatcher) { store.dashboard() }
        mutable.update { it.copy(dashboard=dashboard,projects=projects,project=project,builds=builds,build=builds.find { b -> b.id==buildId } ?: builds.firstOrNull(),results=emptyList()) }
    }
    fun selectProject(id: String) = action { refresh(id,null) }
    fun selectBuild(id: String) { if(mutable.value.busy) return; mutable.update { it.copy(build=it.builds.find { b -> b.id==id },results=emptyList()) } }
    fun createProject(name: String, appId: String) = action {
        val p = withContext(ioDispatcher) { store.createProject(name,appId) }; refresh(p.id,null)
    }
    fun createBuild(name: String, code: String, variant: String) = action {
        val p = mutable.value.project ?: error("Select a project")
        val number = code.toLongOrNull() ?: throw IllegalArgumentException("Version code must be an integer")
        val b = withContext(ioDispatcher) { store.createBuild(p.id,name,number,variant) }; refresh(p.id,b.id)
    }
    fun upload(path: Path, apk: Boolean = false) = action {
        val b = mutable.value.build ?: error("Select a build")
        withContext(ioDispatcher) { store.upload(b.id,path,apk) }; refresh()
    }
    fun clearResults() { mutable.update { it.copy(results=emptyList(),error=null) } }
    fun retrace(traces: List<String>) = action {
        val b = mutable.value.build ?: throw IllegalArgumentException("Select a build")
        mutable.update { it.copy(results=emptyList()) }
        val results = withContext(ioDispatcher) { store.withMapping(b) { engine.retraceBatch(it,traces) }.also { store.recordRetrace(b,it) } }
        val dashboard = withContext(ioDispatcher) { store.dashboard() }
        mutable.update { it.copy(results=results,dashboard=dashboard) }
    }
    fun updateProject(name: String, description: String?) = action {
        val p = mutable.value.project ?: throw IllegalArgumentException("Select a project")
        withContext(ioDispatcher) { store.updateProject(p.id,name,description) }; refresh()
    }
    fun deleteProject() = action {
        mutable.value.project?.let { p -> withContext(ioDispatcher) { store.deleteProject(p.id) }; refresh(null,null) }
    }
    fun deleteBuild() = action {
        mutable.value.build?.let { b -> withContext(ioDispatcher) { store.deleteBuild(b.id) }; refresh(buildId=null) }
    }
    fun deleteMapping() = action { mutable.value.build?.let { b -> withContext(ioDispatcher) { store.deleteMapping(b.id) }; refresh() } }
    override fun close() { scope.cancel() }
}

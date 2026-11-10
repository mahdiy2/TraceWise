@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package studio.stacktrace.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.koin.core.context.stopKoin
import org.koin.core.context.startKoin
import org.koin.dsl.module
import studio.stacktrace.storage.LocalStore
import studio.stacktrace.storage.WorkspaceRepository
import studio.stacktrace.retrace.R8RetraceEngine
import java.nio.file.Files
import java.nio.file.Path
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private fun chooseFile(save: Boolean = false): Path? {
    val dialog = FileDialog(null as Frame?,if(save) "Export translated trace" else "Select file",if(save) FileDialog.SAVE else FileDialog.LOAD)
    if(save) dialog.file="translated.txt"
    return try { dialog.isVisible=true; dialog.file?.let { Path.of(dialog.directory,it) } }
    finally { dialog.dispose() }
}
fun main(args: Array<String>) {
    val smoke=args.contains("--smoke-test")
    val smokeDirectory=if(smoke) Files.createTempDirectory("stacktrace-native-smoke") else null
    val root=smokeDirectory ?: Path.of(System.getenv("STACKTRACE_DATA_DIR") ?: Path.of(System.getProperty("user.home"),".stacktrace-studio").toString())
    val koin = startKoin { modules(module {
        single<WorkspaceRepository> { LocalStore(root) }
        single { R8RetraceEngine() }
        factory { WorkspaceViewModel(get(),get()) }
    }) }.koin
    try {
        if(smoke) {
            val store=koin.get<WorkspaceRepository>()
            val project=store.createProject("Native smoke test","studio.stacktrace.smoke")
            val build=store.createBuild(project.id,"1",1)
            val mapping=root.resolve("mapping.txt")
            Files.writeString(mapping,"com.example.Smoke -> a:\n# {\"id\":\"sourceFile\",\"fileName\":\"Smoke.kt\"}\n    1:1:void crash():7:7 -> b\n")
            store.upload(build.id,mapping)
        }
        application {
            val vm = remember { koin.get<WorkspaceViewModel>() }
            DisposableEffect(vm) { onDispose { vm.close() } }
            Window(onCloseRequest=::exitApplication,title="StackTrace Studio",state=rememberWindowState(width=1200.dp,height=900.dp)) {
                window.minimumSize=java.awt.Dimension(720,600)
                Workspace(vm)
                if(smoke) LaunchedEffect(Unit) {
                    withTimeout(15000) {
                        vm.state.first { !it.busy }
                        vm.retrace(listOf("java.lang.RuntimeException\n\tat a.b(SourceFile:1)"))
                        val state=vm.state.first { !it.busy && it.results.isNotEmpty() }
                        check(state.results.single().translated?.contains("com.example.Smoke.crash(Smoke.kt:7)")==true) { "Native retrace smoke test failed" }
                        withFrameNanos { }
                        withFrameNanos { }
                    }
                    System.getenv("STACKTRACE_SMOKE_REPORT")?.let { Files.writeString(Path.of(it),"STACKTRACE_STUDIO_SMOKE_OK\n") }
                    println("STACKTRACE_STUDIO_SMOKE_OK")
                    exitApplication()
                }
            }
        }
    } finally {
        stopKoin()
        smokeDirectory?.let { directory -> Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
@Composable internal fun Workspace(vm: WorkspaceViewModel) {
    val state by vm.state.collectAsState()
    var confirmation by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf<String?>(null) }
    var editDescription by remember { mutableStateOf("") }
    var dashboardOpen by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(true) }
    var projectName by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var debug by remember { mutableStateOf(false) }
    var trace by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    fun fileAction(action: () -> Unit) { try { action(); fileError=null } catch(_: Exception) { fileError="File operation failed. Check file size and permissions." } }
    MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
        val highlighting = StackTraceHighlighting(MaterialTheme.colorScheme.error,MaterialTheme.colorScheme.primary)
        val traceStyle = androidx.compose.ui.text.TextStyle(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)
        confirmation?.let { kind -> AlertDialog(
            onDismissRequest={confirmation=null},title={Text("Delete $kind?")},
            text={Text("This removes its saved metadata and associations.")},
            confirmButton={TextButton(onClick={if(kind=="project") vm.deleteProject() else vm.deleteBuild(); confirmation=null}) {Text("Delete")}},
            dismissButton={TextButton(onClick={confirmation=null}) {Text("Cancel")}}
        ) }
        editName?.let { name -> AlertDialog(
            onDismissRequest={editName=null},title={Text("Edit project")},
            text={Column { OutlinedTextField(name,{editName=it},label={Text("Name")}); OutlinedTextField(editDescription,{editDescription=it},label={Text("Description")}) }},
            confirmButton={TextButton(onClick={vm.updateProject(name,editDescription.ifBlank { null }); editName=null}) {Text("Save")}},
            dismissButton={TextButton(onClick={editName=null}) {Text("Cancel")}}
        ) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()).onPreviewKeyEvent { event ->
                if(event.type==KeyEventType.KeyDown && event.isCtrlPressed && event.key==Key.Enter && !state.busy && state.build?.mappingSha256!=null) {
                    vm.retrace(if(batch) trace.split(Regex("(?m)^---\\r?$")) else listOf(trace));true
                } else false
            },verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("StackTrace Studio",style=MaterialTheme.typography.headlineMedium)
                    TextButton(onClick={dark=!dark}) { Text(if(dark) "Light theme" else "Dark theme") }
                }
                Text("Local workspace · ${state.dashboard.projectCount} projects · ${state.dashboard.buildCount} total builds")
                TextButton(onClick={dashboardOpen=!dashboardOpen}) {Text(if(dashboardOpen) "Hide dashboard" else "Dashboard")}
                if(dashboardOpen) {
                    Text("Recent builds",style=MaterialTheme.typography.titleMedium)
                    state.dashboard.recentBuilds.take(5).forEach { b -> Text("${state.projects.find { it.id==b.projectId }?.name.orEmpty()} · ${b.versionName} (${b.versionCode}) · ${b.variant}") }
                    if(state.dashboard.recentBuilds.isEmpty()) Text("No builds yet")
                    Text("Recent retrace operations",style=MaterialTheme.typography.titleMedium)
                    state.dashboard.recentRetraces.take(5).forEach { r -> Text("${r.projectName} · ${r.versionName} (${r.versionCode}) · ${r.successCount}/${r.traceCount} translated · ${r.createdAt}") }
                    if(state.dashboard.recentRetraces.isEmpty()) Text("No retrace operations yet")
                }
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                (state.error ?: fileError)?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Text("Projects",style=MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(projectName,{projectName=it},label={Text("Project name")},modifier=Modifier.weight(1f))
                    OutlinedTextField(appId,{appId=it},label={Text("Application ID")},modifier=Modifier.weight(1f))
                    Button(onClick={vm.createProject(projectName,appId)},enabled=!state.busy) { Text("Create") }
                }
                state.projects.forEach { p ->
                    TextButton(onClick={vm.selectProject(p.id)},enabled=!state.busy) { Text("${if(p.id==state.project?.id) "✓ " else ""}${p.name} · ${p.applicationId}") }
                }
                if(state.project!=null) {
                    Row {
                        TextButton(onClick={editName=state.project?.name; editDescription=state.project?.description.orEmpty()},enabled=!state.busy) {Text("Edit selected project")}
                        TextButton(onClick={confirmation="project"},enabled=!state.busy) {Text("Delete selected project")}
                    }
                    HorizontalDivider()
                    Text("Builds",style=MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(version,{version=it},label={Text("Version name")},modifier=Modifier.weight(1f))
                        OutlinedTextField(code,{code=it},label={Text("Version code")},modifier=Modifier.weight(1f))
                        Checkbox(debug,{debug=it}); Text("Debug")
                        Button(onClick={vm.createBuild(version,code,if(debug) "debug" else "release")},enabled=!state.busy) { Text("Add build") }
                    }
                    state.builds.forEach { b ->
                        TextButton(onClick={vm.selectBuild(b.id)},enabled=!state.busy) { Text("${if(b.id==state.build?.id) "✓ " else ""}${b.versionName} (${b.versionCode}) · ${b.variant} · ${if(b.mappingSha256!=null) "Mapping ready" else "Mapping missing"}") }
                    }
                }
                if(state.build!=null) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={fileAction { chooseFile()?.let { vm.upload(it) } }},enabled=!state.busy) { Text("Upload mapping.txt") }
                        OutlinedButton(onClick={fileAction { chooseFile()?.let { vm.upload(it,true) } }},enabled=!state.busy) { Text("Upload APK (optional)") }
                        TextButton(onClick=vm::deleteMapping,enabled=!state.busy && state.build?.mappingSha256!=null) { Text("Remove mapping") }
                        TextButton(onClick={confirmation="build"},enabled=!state.busy) {Text("Delete build")}
                    }
                    Text("Retrace · Ctrl+Enter",style=MaterialTheme.typography.titleLarge)
                    Row {
                        Checkbox(batch,{batch=it;vm.clearResults()},enabled=!state.busy); Text("Batch: separate traces with a line containing ---")
                    }
                    BoxWithConstraints {
                        @Composable fun original(modifier: Modifier) { OutlinedTextField(trace,{trace=it;vm.clearResults()},enabled=!state.busy,label={Text("Original stack trace")},visualTransformation=highlighting,textStyle=traceStyle,modifier=modifier.height(280.dp).testTag("trace-input")) }
                        @Composable fun translated(modifier: Modifier) { OutlinedTextField(state.results.joinToString("\n\n---\n\n") { it.translated ?: "${it.errorCode}: ${it.message}" },{},readOnly=true,label={Text("Translated stack trace")},visualTransformation=highlighting,textStyle=traceStyle,modifier=modifier.height(280.dp).testTag("trace-output")) }
                        if(maxWidth<900.dp) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            original(Modifier.fillMaxWidth());translated(Modifier.fillMaxWidth())
                        } else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            original(Modifier.weight(1f));translated(Modifier.weight(1f))
                        }
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={fileAction { chooseFile()?.let { require(Files.size(it)<=1024*1024); trace=Files.readString(it);vm.clearResults() } }},enabled=!state.busy) { Text("Load TXT") }
                        Button(onClick={vm.retrace(if(batch) trace.split(Regex("(?m)^---\\r?$")) else listOf(trace))},enabled=!state.busy && state.build?.mappingSha256!=null) { Text("Retrace") }
                        val output = state.results.mapNotNull { it.translated }.joinToString("\n\n---\n\n")
                        OutlinedButton(onClick={fileAction { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(output),null) }},enabled=output.isNotEmpty() && !state.busy) { Text("Copy") }
                        OutlinedButton(onClick={fileAction { chooseFile(true)?.let { Files.writeString(it,output) } }},enabled=output.isNotEmpty() && !state.busy) { Text("Export TXT") }
                    }
                } else Text("Create a project and build, then upload its mapping to begin.")
            }
        }
    }
}

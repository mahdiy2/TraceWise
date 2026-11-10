@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package studio.stacktrace.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.koin.dsl.koinApplication
import org.koin.dsl.module

fun main() {
    document.getElementById("loading")?.remove()
    val container=koinApplication { modules(module { single { WebViewModel() } }) }
    ComposeViewport(document.body!!) { WebWorkspace(container.koin.get()) }
}
@Composable private fun WebWorkspace(vm: WebViewModel) {
    val state by vm.state.collectAsState()
    var token by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var debug by remember { mutableStateOf(false) }
    var trace by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var buildMenu by remember { mutableStateOf(false) }
    DisposableEffect(vm) { onDispose { vm.close() } }
    MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
        val highlighting=StackTraceHighlighting(MaterialTheme.colorScheme.error,MaterialTheme.colorScheme.primary)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()).onPreviewKeyEvent { event ->
                if(event.type==KeyEventType.KeyDown && event.isCtrlPressed && event.key==Key.Enter && !state.busy && state.build?.mappingSha256!=null) {
                    vm.retrace(if(batch) trace.split(Regex("(?m)^---\\r?$")) else listOf(trace));true
                } else false
            },verticalArrangement=Arrangement.spacedBy(16.dp)) {
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column { Text("StackTrace Studio",style=MaterialTheme.typography.headlineMedium); Text("Android crash deobfuscation",style=MaterialTheme.typography.bodyMedium) }
                    Row {
                        TextButton(onClick={dark=!dark}) {Text(if(dark) "Light theme" else "Dark theme")}
                        if(state.connected) TextButton(onClick={vm.disconnect(); token=""; trace=""},enabled=!state.busy) {Text("Disconnect")}
                    }
                }
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it,color=MaterialTheme.colorScheme.primary) }
                if(!state.connected) {
                    Text("Connect to your workspace",style=MaterialTheme.typography.titleLarge)
                    Text("Enter the API token configured on this server. Credentials stay in this browser session's memory.")
                    OutlinedTextField(token,{token=it},enabled=!state.busy,label={Text("API token")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={vm.connect(token)},enabled=!state.busy) {Text("Connect")}
                    Text("Local development mode can connect with an empty token.")
                } else {
                    Text("${state.projects.size} applications · ${state.builds.size} builds in selected application")
                    BoxWithConstraints {
                        val compact=maxWidth<760.dp
                        @Composable fun projectControls() {
                            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("Application",style=MaterialTheme.typography.titleMedium)
                                Box {
                                    OutlinedButton(onClick={projectMenu=true},enabled=!state.busy) {Text(state.project?.let { "${it.name} · ${it.applicationId}" } ?: "Select an application")}
                                    DropdownMenu(expanded=projectMenu,onDismissRequest={projectMenu=false}) {
                                        state.projects.forEach { p -> DropdownMenuItem(text={Text(p.name)},onClick={vm.selectProject(p.id); projectMenu=false}) }
                                    }
                                }
                                Row {
                                    TextButton(onClick={name="";appId="";dialog="project"},enabled=!state.busy) {Text("New application")}
                                    TextButton(onClick={name=state.project?.name.orEmpty();description=state.project?.description.orEmpty();dialog="edit"},enabled=!state.busy && state.project!=null) {Text("Edit")}
                                    TextButton(onClick={if(confirmDelete("Delete application and all its builds?")) vm.deleteProject()},enabled=!state.busy && state.project!=null) {Text("Delete")}
                                }
                            }
                        }
                        @Composable fun buildControls() {
                            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("Build",style=MaterialTheme.typography.titleMedium)
                                Box {
                                    OutlinedButton(onClick={buildMenu=true},enabled=!state.busy && state.project!=null) {Text(state.build?.let { "${it.versionName} (${it.versionCode}) · ${it.variant}" } ?: "Select a build")}
                                    DropdownMenu(expanded=buildMenu,onDismissRequest={buildMenu=false}) {
                                        state.builds.forEach { b -> DropdownMenuItem(text={Text("${b.versionName} (${b.versionCode}) · ${b.variant}")},onClick={vm.selectBuild(b.id);buildMenu=false}) }
                                    }
                                }
                                Row {
                                    TextButton(onClick={version="";code="";debug=false;dialog="build"},enabled=!state.busy && state.project!=null) {Text("New build")}
                                    TextButton(onClick={if(confirmDelete("Delete this build?")) vm.deleteBuild()},enabled=!state.busy && state.build!=null) {Text("Delete build")}
                                }
                            }
                        }
                        if(compact) Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {projectControls();buildControls()}
                        else Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {Box(Modifier.weight(1f)) {projectControls()};Box(Modifier.weight(1f)) {buildControls()}}
                    }
                    state.build?.let { b ->
                        HorizontalDivider()
                        Text(if(b.mappingSha256==null) "Mapping required — APK upload is optional" else "Mapping ready · SHA-256 ${b.mappingSha256}",style=MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={vm.upload(false)},enabled=!state.busy) {Text(if(b.mappingSha256==null) "Upload mapping.txt" else "Replace mapping")}
                            OutlinedButton(onClick={vm.upload(true)},enabled=!state.busy) {Text("Upload APK")}
                            TextButton(onClick={if(confirmDelete("Remove this build's mapping?")) vm.deleteMapping()},enabled=!state.busy && b.mappingSha256!=null) {Text("Remove mapping")}
                        }
                        Row {Checkbox(batch,{batch=it;vm.clearResults()},enabled=!state.busy);Text("Batch mode · separate traces with a line containing --- · Ctrl+Enter to retrace")}
                        val output=state.results.joinToString("\n\n---\n\n") { it.translated ?: "${it.errorCode}: ${it.message}" }
                        val style=MaterialTheme.typography.bodyMedium.copy(fontFamily=FontFamily.Monospace)
                        BoxWithConstraints {
                            @Composable fun original(modifier: Modifier) {OutlinedTextField(trace,{trace=it;vm.clearResults()},enabled=!state.busy,label={Text("Original stack trace")},visualTransformation=highlighting,textStyle=style,modifier=modifier.height(280.dp))}
                            @Composable fun translated(modifier: Modifier) {OutlinedTextField(output,{},readOnly=true,label={Text("Translated stack trace")},visualTransformation=highlighting,textStyle=style,modifier=modifier.height(280.dp))}
                            if(maxWidth<760.dp) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {original(Modifier.fillMaxWidth());translated(Modifier.fillMaxWidth())}
                            else Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {original(Modifier.weight(1f));translated(Modifier.weight(1f))}
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={vm.action { loadText()?.let { trace=it;vm.clearResults() } }},enabled=!state.busy) {Text("Load TXT")}
                            Button(onClick={vm.retrace(if(batch) trace.split(Regex("(?m)^---\\r?$")) else listOf(trace))},enabled=!state.busy && b.mappingSha256!=null) {Text("Retrace")}
                            val successful=state.results.mapNotNull { it.translated }.joinToString("\n\n---\n\n")
                            OutlinedButton(onClick={vm.action {copyText(successful)}},enabled=successful.isNotEmpty() && !state.busy) {Text("Copy")}
                            OutlinedButton(onClick={downloadText(successful)},enabled=successful.isNotEmpty() && !state.busy) {Text("Download TXT")}
                        }
                        state.results.forEachIndexed { i,r -> Text("Trace ${i+1}: ${r.status}${if(r.warnings.isEmpty()) "" else " · ${r.warnings.joinToString()}"}",style=MaterialTheme.typography.bodySmall) }
                    } ?: Text("Create an application and a build, then upload mapping.txt to start retracing.")
                }
            }
            dialog?.let { kind -> AlertDialog(
                onDismissRequest={dialog=null},title={Text(when(kind) {"project"->"New application";"edit"->"Edit application";else->"New build"})},
                text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(kind=="build") {
                        OutlinedTextField(version,{version=it},label={Text("Version name")},singleLine=true)
                        OutlinedTextField(code,{code=it},label={Text("Version code")},singleLine=true)
                        Row {Checkbox(debug,{debug=it});Text("Debug variant")}
                    } else {
                        OutlinedTextField(name,{name=it},label={Text("Name")},singleLine=true)
                        if(kind=="project") OutlinedTextField(appId,{appId=it},label={Text("Application ID")},singleLine=true)
                        else OutlinedTextField(description,{description=it},label={Text("Description")})
                    }
                }},
                confirmButton={TextButton(onClick={when(kind) {"project"->vm.createProject(name,appId);"edit"->vm.updateProject(name,description);else->vm.createBuild(version,code,if(debug) "debug" else "release")};dialog=null}) {Text("Save")}},
                dismissButton={TextButton(onClick={dialog=null}) {Text("Cancel")}}
            ) }
        }
    }
}

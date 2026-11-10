package studio.stacktrace.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import studio.stacktrace.storage.LocalStore
import studio.stacktrace.retrace.R8RetraceEngine
import java.awt.GraphicsEnvironment
import java.nio.file.Files

@OptIn(ExperimentalTestApi::class)
class DesktopRenderTest {
    @get:Rule val ui=createComposeRule()
    @Test fun rendersAndTranslatesUsingLocalMappingWithoutServer() {
        assumeFalse("Desktop rendering requires a display or Xvfb",GraphicsEnvironment.isHeadless())
        val directory=Files.createTempDirectory("studio-desktop-render")
        val store=LocalStore(directory)
        val project=store.createProject("Render test","com.example.render")
        val build=store.createBuild(project.id,"1",1)
        val mapping=directory.resolve("mapping.txt")
        Files.writeString(mapping,"com.example.Crash -> a:\n    1:1:void explode():42:42 -> b\n")
        store.upload(build.id,mapping)
        val vm=WorkspaceViewModel(store,R8RetraceEngine())
        try {
            ui.setContent {Workspace(vm)}
            ui.waitUntil(10000) {!vm.state.value.busy && vm.state.value.build!=null}
            ui.onNodeWithTag("trace-input").performScrollTo().performTextInput("java.lang.RuntimeException\n\tat a.b(SourceFile:1)")
            ui.onNode(hasText("Retrace") and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(10000) {!vm.state.value.busy && vm.state.value.results.isNotEmpty()}
            ui.onNodeWithTag("trace-output").performScrollTo().assertTextContains("com.example.Crash.explode(Crash.java:42)",substring=true)
            ui.onNodeWithTag("trace-input").performScrollTo().performClick().performKeyInput {
                keyDown(Key.CtrlLeft);pressKey(Key.Enter);keyUp(Key.CtrlLeft)
            }
            ui.waitUntil(10000) {!vm.state.value.busy && vm.state.value.dashboard.recentRetraces.size==2}
        } finally {
            vm.close()
            Files.walk(directory).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}
        }
    }
}

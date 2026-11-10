package studio.stacktrace.desktop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import studio.stacktrace.storage.LocalStore
import studio.stacktrace.retrace.R8RetraceEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    private fun scenario(block: suspend TestScope.(WorkspaceViewModel,Path)->Unit) = runTest {
        val directory=Files.createTempDirectory("studio-vm")
        val dispatcher=StandardTestDispatcher(testScheduler)
        val vm=WorkspaceViewModel(LocalStore(directory),R8RetraceEngine(),dispatcher,dispatcher)
        try { block(vm,directory) }
        finally {vm.close();Files.walk(directory).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
    @Test fun loadingErrorAndRecovery() = scenario {vm,_ ->
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.projects.isEmpty())
        vm.createProject("App","invalid")
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        vm.createProject("App","com.example.app")
        assertNull(vm.state.value.error)
        advanceUntilIdle()
        assertEquals("App",vm.state.value.project?.name)
        assertFalse(vm.state.value.busy)
    }
    @Test fun uploadRetraceAndInvalidation() = scenario {vm,dir ->
        advanceUntilIdle();vm.createProject("App","com.example.app");advanceUntilIdle()
        vm.createBuild("1","1","release");advanceUntilIdle()
        val mapping=dir.resolve("mapping.txt")
        Files.writeString(mapping,"com.example.Crash -> a:\n    1:1:void explode():42:42 -> b\n")
        vm.upload(mapping);advanceUntilIdle()
        assertNotNull(vm.state.value.build?.mappingSha256)
        val original="java.lang.RuntimeException\n\tat a.b(SourceFile:1)"
        vm.retrace(listOf(original));advanceUntilIdle()
        val result=vm.state.value.results.single()
        assertEquals(1,vm.state.value.dashboard.recentRetraces.single().successCount)
        assertEquals(original,result.original)
        assertContains(result.translated!!,"com.example.Crash.explode(Crash.java:42)")
        vm.clearResults()
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals(1,vm.state.value.dashboard.recentRetraces.size)
        vm.deleteMapping();advanceUntilIdle()
        assertNull(vm.state.value.build?.mappingSha256)
        assertTrue(vm.state.value.results.isEmpty())
        vm.retrace(listOf(original));advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.results.isEmpty())
    }
    @Test fun rejectsRepeatedActionsAndClearsSelectionAfterDeletion() = scenario {vm,_ ->
        advanceUntilIdle()
        vm.createProject("First","com.example.first")
        vm.createProject("Second","com.example.second")
        advanceUntilIdle()
        assertEquals(1,vm.state.value.projects.size)
        vm.createBuild("1","not-integer","release");advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        vm.deleteProject();advanceUntilIdle()
        assertNull(vm.state.value.project)
        assertNull(vm.state.value.build)
        assertTrue(vm.state.value.projects.isEmpty())
    }
}

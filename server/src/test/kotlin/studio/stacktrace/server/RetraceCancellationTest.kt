package studio.stacktrace.server

import kotlinx.coroutines.*
import studio.stacktrace.storage.LocalStore
import studio.stacktrace.retrace.R8RetraceEngine
import java.nio.file.Files
import kotlin.test.*

class RetraceCancellationTest {
    @Test fun cancelledActiveRequestReleasesSnapshotAndAdmissionPermit() = runBlocking {
        val directory=Files.createTempDirectory("studio-cancel")
        val store=LocalStore(directory)
        val project=store.createProject("App","com.example.app")
        val build=store.createBuild(project.id,"1",1)
        val mapping=directory.resolve("mapping.txt")
        Files.writeString(mapping,"com.example.Crash -> a:\n    1:1:void explode():42:42 -> b\n")
        store.upload(build.id,mapping)
        val service=RetraceService(store,R8RetraceEngine(),1)
        try {
            val request=async(Dispatchers.Default) {
                service.run(build.id,null,null,null,null,listOf("\tat a.b(SourceFile:1)\n".repeat(20000)))
            }
            withTimeout(10000) {
                while(Files.list(directory.resolve("snapshots")).use {it.count()}==0L) {
                    check(!request.isCompleted) {"Retrace finished before active snapshot could be observed"}
                    delay(1)
                }
            }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            Files.list(directory.resolve("snapshots")).use {assertEquals(0L,it.count())}
            val next=withTimeout(10000) {service.run(build.id,null,null,null,null,listOf("\tat a.b(SourceFile:1)"))}
            assertTrue(next.single().success)
            assertContains(next.single().translated!!,"Crash.java:42")
        } finally {
            Files.walk(directory).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}
        }
    }
}

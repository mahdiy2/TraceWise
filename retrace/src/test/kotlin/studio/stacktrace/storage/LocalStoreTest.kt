package studio.stacktrace.storage

import java.nio.file.Files
import kotlin.test.*

class LocalStoreTest {
    private fun fixture(body: (LocalStore, java.nio.file.Path) -> Unit) {
        val dir = Files.createTempDirectory("studio-store")
        try { body(LocalStore(dir),dir) }
        finally { Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun persistsAndResolvesBuildsAcrossInstances() = fixture { store, dir ->
        val p = store.createProject("Example","com.example.app")
        val b = store.createBuild(p.id,"1.0",1)
        assertEquals(b,LocalStore(dir).resolveBuild("com.example.app",1))
        store.createBuild(p.id,"1.0",1,"debug")
        assertFailsWith<IllegalArgumentException> { store.resolveBuild("com.example.app",1) }
        assertEquals(b,store.resolveBuild("com.example.app",1,"release"))
        store.deleteProject(p.id)
        assertTrue(store.builds(p.id).isEmpty())
    }
    @Test fun validatesMappingAndKeepsOldArtifactAfterFailedReplacement() = fixture { store, dir ->
        val p = store.createProject("Example","com.example.app")
        val b = store.createBuild(p.id,"1",1)
        val mapping = dir.resolve("mapping.txt")
        Files.writeString(mapping,"com.example.Crash -> a:\n    1:1:void explode():42:42 -> b\n")
        val saved = store.upload(b.id,mapping)
        assertEquals(64,saved.mappingSha256!!.length)
        val original = Files.readString(store.mappingPath(b.id))
        Files.writeString(mapping,"not a mapping")
        assertFailsWith<IllegalArgumentException> { store.upload(b.id,mapping) }
        assertEquals(original,Files.readString(store.mappingPath(b.id)))
        assertEquals(saved,store.getBuild(b.id))
        store.deleteMapping(b.id)
        assertNull(store.getBuild(b.id).mappingSha256)
        assertFails { store.mappingPath(b.id) }
    }
    @Test fun replacementReclaimsOldBlobButKeepsActiveRetraceSnapshot() = fixture { store, dir ->
        val project=store.createProject("App","com.example.app")
        val build=store.createBuild(project.id,"1",1)
        val mapping=dir.resolve("mapping.txt")
        Files.writeString(mapping,"com.example.Crash -> a:\n    1:1:void explode():42:42 -> b\n")
        val original=store.upload(build.id,mapping)
        val originalPath=store.mappingPath(build.id)
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            store.withMapping(original) { snapshot ->
                val replacement=executor.submit<studio.stacktrace.ApplicationBuild> {
                    Files.writeString(mapping,"com.example.Crash -> a:\n    1:1:void explode():99:99 -> b\n")
                    LocalStore(dir).upload(build.id,mapping)
                }.get(10,java.util.concurrent.TimeUnit.SECONDS)
                assertNotEquals(original.mappingSha256,replacement.mappingSha256)
                assertFalse(Files.exists(originalPath))
                assertTrue(Files.exists(snapshot))
                val result=studio.stacktrace.retrace.R8RetraceEngine().retrace(snapshot,"\tat a.b(SourceFile:1)")
                assertContains(result.translated!!,"Crash.java:42")
                store.withMapping(replacement) { current ->
                    assertContains(studio.stacktrace.retrace.R8RetraceEngine().retrace(current,"\tat a.b(SourceFile:1)").translated!!,"Crash.java:99")
                }
            }
            Files.list(dir.resolve("snapshots")).use { assertEquals(0L,it.count()) }
        } finally {executor.shutdownNow()}
    }
    @Test fun sharedBlobIsReclaimedOnlyAfterLastReferenceAndSnapshot() = fixture { store, dir ->
        val project=store.createProject("App","com.example.app")
        val first=store.createBuild(project.id,"1",1)
        val second=store.createBuild(project.id,"2",2)
        val mapping=dir.resolve("mapping.txt")
        Files.writeString(mapping,"com.example.Crash -> a:\n    void explode() -> b\n")
        store.upload(first.id,mapping)
        val saved=store.upload(second.id,mapping)
        val blob=store.mappingPath(second.id)
        store.deleteMapping(first.id)
        assertTrue(Files.exists(blob))
        store.withMapping(saved) { snapshot ->
            store.deleteProject(project.id)
            assertFalse(Files.exists(blob))
            assertTrue(Files.readString(snapshot).contains("com.example.Crash"))
            LocalStore(dir).pruneUnusedArtifacts()
            assertTrue(Files.exists(snapshot))
        }
        Files.list(dir.resolve("snapshots")).use { assertEquals(0L,it.count()) }
    }
    @Test fun concurrentPublishAndCleanupKeepEveryReferencedArtifact() = fixture { store, dir ->
        val project=store.createProject("App","com.example.app")
        val builds=(1L..8L).map {store.createBuild(project.id,"$it",it)}
        val mapping=dir.resolve("mapping.txt")
        Files.writeString(mapping,"com.example.Crash -> a:\n    void explode() -> b\n")
        val executor=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks=builds.map { b -> executor.submit<studio.stacktrace.ApplicationBuild> {LocalStore(dir).upload(b.id,mapping)} }
            repeat(5) {store.pruneUnusedArtifacts()}
            val saved=tasks.map {it.get(10,java.util.concurrent.TimeUnit.SECONDS)}
            assertEquals(1,saved.map {it.mappingSha256}.toSet().size)
            saved.forEach {assertTrue(Files.isRegularFile(store.mappingPath(it.id)))}
            Files.list(dir.resolve("artifacts")).use {assertEquals(1L,it.count())}
        } finally {executor.shutdownNow()}
    }
    @Test fun snapshotFailureAndOrphanCleanupLeaveNoTemporaryFiles() = fixture { store, dir ->
        val project=store.createProject("App","com.example.app")
        val build=store.createBuild(project.id,"1",1)
        val mapping=dir.resolve("mapping.txt")
        Files.writeString(mapping,"com.example.Crash -> a:\n    void explode() -> b\n")
        val saved=store.upload(build.id,mapping)
        assertFailsWith<IllegalStateException> {store.withMapping(saved) {error("operation failed")}}
        Files.list(dir.resolve("snapshots")).use {assertEquals(0L,it.count())}
        val orphan=dir.resolve("artifacts").resolve("a".repeat(64))
        Files.writeString(orphan,"orphaned blob")
        assertEquals(1,store.pruneUnusedArtifacts())
        assertFalse(Files.exists(orphan))
        assertTrue(Files.exists(store.mappingPath(build.id)))
    }
    @Test fun dashboardCountsAllProjectsAndKeepsOnlyOperationMetadata() = fixture { store, dir ->
        val first=store.createProject("First","com.example.first")
        val second=store.createProject("Second","com.example.second")
        val build=store.createBuild(first.id,"1",1)
        store.createBuild(second.id,"2",2)
        store.recordRetrace(build,listOf(studio.stacktrace.RetraceResult("sensitive-original","sensitive-translated",studio.stacktrace.RetraceStatus.SUCCESS)))
        val dashboard=LocalStore(dir).dashboard()
        assertEquals(2,dashboard.projectCount)
        assertEquals(2,dashboard.buildCount)
        assertEquals(1,dashboard.recentRetraces.single().successCount)
        assertEquals("First",dashboard.recentRetraces.single().projectName)
        val database=Files.readAllBytes(dir.resolve("studio.db")).toString(Charsets.ISO_8859_1)
        assertFalse(database.contains("sensitive-original"))
        assertFalse(database.contains("sensitive-translated"))
    }
    @Test fun optionalApkStaysUntilBuildDeletionAndIsNeverExecuted() = fixture { store, dir ->
        val project=store.createProject("App","com.example.app")
        val build=store.createBuild(project.id,"1",1)
        val apk=dir.resolve("sample.apk")
        java.util.zip.ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zip.write("archive fixture".toByteArray())
            zip.closeEntry()
        }
        val saved=store.upload(build.id,apk,apk=true)
        val artifact=dir.resolve("artifacts").resolve(saved.apkSha256!!)
        assertContentEquals(Files.readAllBytes(apk),Files.readAllBytes(artifact))
        store.deleteMapping(build.id)
        assertTrue(Files.exists(artifact))
        store.deleteBuild(build.id)
        assertFalse(Files.exists(artifact))
    }
    @Test fun rejectsInvalidApkAndCleansTemporaryUpload() = fixture { store, dir ->
        val p = store.createProject("App","com.example.app")
        val b = store.createBuild(p.id,"1",1)
        val apk = dir.resolve("invalid.apk")
        Files.writeString(apk,"not an archive")
        assertFailsWith<IllegalArgumentException> { store.upload(b.id,apk,apk=true) }
        assertNull(store.getBuild(b.id).apkSha256)
        Files.list(dir.resolve("artifacts")).use { assertEquals(0L,it.count()) }
    }
    @Test fun rejectsInvalidIdentifiersAndDuplicateVersions() = fixture { store, _ ->
        assertFailsWith<IllegalArgumentException> { store.createProject("App","../app") }
        val p = store.createProject("App","com.example.app")
        store.createBuild(p.id,"1",1)
        assertFails { store.createBuild(p.id,"duplicate",1) }
        assertFails { store.createBuild("missing","1",1) }
    }
}

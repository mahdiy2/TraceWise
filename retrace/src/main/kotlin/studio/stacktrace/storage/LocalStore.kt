package studio.stacktrace.storage

import studio.stacktrace.*
import studio.stacktrace.retrace.R8RetraceEngine
import java.nio.file.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

/** Blocking local persistence shared by desktop and server. One connection per operation. */
class LocalStore(private val directory: Path, private val mappingLimit: Long = 50L*1024*1024, private val apkLimit: Long = 500L*1024*1024) : WorkspaceRepository {
    init {
        require(mappingLimit>0 && apkLimit>0) { "Artifact size limits must be positive" }
        Files.createDirectories(directory.resolve("artifacts"))
        Files.createDirectories(directory.resolve("snapshots"))
        connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,application_id TEXT NOT NULL UNIQUE,description TEXT,created_at TEXT NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS retraces(id TEXT PRIMARY KEY,project_name TEXT NOT NULL,version_name TEXT NOT NULL,version_code INTEGER NOT NULL,trace_count INTEGER NOT NULL,success_count INTEGER NOT NULL,created_at TEXT NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS builds(id TEXT PRIMARY KEY,project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,version_name TEXT NOT NULL,version_code INTEGER NOT NULL,variant TEXT NOT NULL,mapping_hash TEXT,apk_hash TEXT,created_at TEXT NOT NULL,UNIQUE(project_id,version_code,variant))")
        } }
        pruneUnusedArtifacts()
    }
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("studio.db").toAbsolutePath()}").also {
        it.createStatement().use { s -> s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000") }
    }
    private fun project(r: ResultSet) = ApplicationProject(r.getString("id"),r.getString("name"),r.getString("application_id"),r.getString("description"),r.getString("created_at"))
    private fun build(r: ResultSet) = ApplicationBuild(r.getString("id"),r.getString("project_id"),r.getString("version_name"),r.getLong("version_code"),r.getString("variant"),r.getString("mapping_hash"),r.getString("apk_hash"),r.getString("created_at"))
    private fun execute(sql: String, vararg values: Any?) = connection().use { c -> c.prepareStatement(sql).use { p ->
        values.forEachIndexed { i,v -> p.setObject(i+1,v) }; p.executeUpdate()
    } }
    override fun projects(): List<ApplicationProject> = connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT * FROM projects ORDER BY created_at DESC").use { r -> buildList { while(r.next()) add(project(r)) } } } }
    override fun createProject(name: String, applicationId: String, description: String?): ApplicationProject {
        require(name.isNotBlank() && name.length <= 200) { "Project name is required (maximum 200 characters)" }
        require(applicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid application ID" }
        val p = ApplicationProject(UUID.randomUUID().toString(),name.trim(),applicationId,description,Instant.now().toString())
        execute("INSERT INTO projects VALUES(?,?,?,?,?)",p.id,p.name,p.applicationId,p.description,p.createdAt)
        return p
    }
    override fun updateProject(id: String, name: String, description: String?) {
        require(name.isNotBlank() && name.length <= 200) { "Project name is required (maximum 200 characters)" }
        require(execute("UPDATE projects SET name=?,description=? WHERE id=?",name.trim(),description,id)==1) { "Project not found" }
    }
    override fun builds(projectId: String): List<ApplicationBuild> = connection().use { c -> c.prepareStatement("SELECT * FROM builds WHERE project_id=? ORDER BY version_code DESC").use { p ->
        p.setString(1,projectId); p.executeQuery().use { r -> buildList { while(r.next()) add(build(r)) } }
    } }
    override fun getBuild(id: String): ApplicationBuild = connection().use { c -> c.prepareStatement("SELECT * FROM builds WHERE id=?").use { p ->
        p.setString(1,id); p.executeQuery().use { r -> require(r.next()) { "Build not found" }; build(r) }
    } }
    override fun createBuild(projectId: String, versionName: String, versionCode: Long, variant: String): ApplicationBuild {
        require(versionName.isNotBlank() && versionName.length <= 100) { "Version name is required (maximum 100 characters)" }
        require(versionCode>=0) { "Version code must be nonnegative" }
        require(variant in listOf("debug","release")) { "Variant must be debug or release" }
        val b = ApplicationBuild(UUID.randomUUID().toString(),projectId,versionName,versionCode,variant,createdAt=Instant.now().toString())
        execute("INSERT INTO builds VALUES(?,?,?,?,?,NULL,NULL,?)",b.id,b.projectId,b.versionName,b.versionCode,b.variant,b.createdAt)
        return b
    }
    fun resolveBuild(applicationId: String, versionCode: Long, variant: String? = null): ApplicationBuild {
        val project = projects().singleOrNull { it.applicationId == applicationId } ?: error("Application not found")
        val matches = builds(project.id).filter { it.versionCode == versionCode && (variant == null || it.variant == variant) }
        require(matches.size == 1) { if(matches.isEmpty()) "Build not found" else "Build variant is ambiguous" }
        return matches.single()
    }
    override fun recordRetrace(build: ApplicationBuild, results: List<RetraceResult>) {
        val projectName=projects().find { it.id==build.projectId }?.name ?: "Deleted application"
        execute("INSERT INTO retraces VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),projectName,build.versionName,build.versionCode,results.size,results.count { it.status==RetraceStatus.SUCCESS },Instant.now().toString())
        execute("DELETE FROM retraces WHERE id NOT IN (SELECT id FROM retraces ORDER BY created_at DESC LIMIT 100)")
    }
    /** Dashboard history stores counts and build metadata; never crash or mapping contents. */
    override fun dashboard(): DashboardSummary = connection().use { c -> c.createStatement().use { st ->
        val projects=st.executeQuery("SELECT COUNT(*) FROM projects").use { r -> r.next();r.getInt(1) }
        val builds=st.executeQuery("SELECT COUNT(*) FROM builds").use { r -> r.next();r.getInt(1) }
        val recentBuilds=st.executeQuery("SELECT * FROM builds ORDER BY created_at DESC LIMIT 10").use { r -> buildList {while(r.next())add(build(r))} }
        val history=st.executeQuery("SELECT * FROM retraces ORDER BY created_at DESC LIMIT 10").use { r -> buildList {
            while(r.next())add(RecentRetrace(r.getString("id"),r.getString("project_name"),r.getString("version_name"),r.getLong("version_code"),r.getInt("trace_count"),r.getInt("success_count"),r.getString("created_at")))
        } }
        DashboardSummary(projects,builds,recentBuilds,history)
    } }
    private fun artifact(hash: String): Path {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return directory.resolve("artifacts").resolve(hash)
    }
    fun mappingPath(buildId: String): Path = mappingPath(getBuild(buildId))
    private fun mappingPath(build: ApplicationBuild): Path = artifact(requireNotNull(build.mappingSha256) { "Mapping is missing" })

    private fun <T> writeTransaction(block: (Connection) -> T): T = connection().use { c ->
        c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result=block(c)
            c.createStatement().use { it.execute("COMMIT") }
            result
        } catch(error: Throwable) {
            runCatching { c.createStatement().use { it.execute("ROLLBACK") } }
            throw error
        }
    }

    /** Pins this build's immutable hash for the entire operation, even after replacement/deletion.
     * Hard links avoid copying mappings; unsupported filesystems use a bounded on-disk copy.
     * The SQLite write reservation serializes pin creation with publication and artifact cleanup.
     */
    override fun <T> withMapping(build: ApplicationBuild, block: (Path) -> T): T {
        val snapshot=directory.resolve("snapshots").resolve("${UUID.randomUUID()}.txt")
        try {
            writeTransaction {
                val source=mappingPath(build)
                try { Files.createLink(snapshot,source) }
                catch(_: UnsupportedOperationException) { Files.copy(source,snapshot) }
                catch(error: FileSystemException) {
                    if(error is NoSuchFileException) throw error
                    Files.copy(source,snapshot)
                }
            }
            return block(snapshot)
        } finally { Files.deleteIfExists(snapshot) }
    }

    /** Published artifacts are immutable. Active operations own separate snapshot links/copies. */
    fun pruneUnusedArtifacts(): Int = writeTransaction { c ->
        val used=c.createStatement().use { st ->
            st.executeQuery("SELECT mapping_hash AS hash FROM builds WHERE mapping_hash IS NOT NULL UNION SELECT apk_hash FROM builds WHERE apk_hash IS NOT NULL").use { r ->
                buildSet {while(r.next())add(r.getString("hash"))}
            }
        }
        var removed=0
        Files.list(directory.resolve("artifacts")).use { files -> files.forEach { path ->
            val hash=path.fileName.toString()
            if(hash.matches(Regex("[a-f0-9]{64}")) && hash !in used && Files.isRegularFile(path)) {
                if(Files.deleteIfExists(path)) removed++
            }
        } }
        removed
    }
    /** Content-addressed immutable artifacts make failed replacements leave old mappings intact. */
    override fun upload(buildId: String, source: Path, apk: Boolean): ApplicationBuild {
        getBuild(buildId)
        require(source.fileName.toString().endsWith(if(apk) ".apk" else ".txt",ignoreCase=true)) { "Unsupported file extension" }
        val max = if(apk) apkLimit else mappingLimit
        val temp = Files.createTempFile(directory.resolve("artifacts"),"upload-",".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            Files.newInputStream(source).use { input -> Files.newOutputStream(temp).use { output ->
                val buffer = ByteArray(65536)
                while(true) {
                    val n = input.read(buffer); if(n<0) break
                    total+=n; require(total<=max) { "Upload exceeds file size limit" }
                    digest.update(buffer,0,n); output.write(buffer,0,n)
                }
            } }
            require(total>0) { "File is empty" }
            if(apk) {
                try {
                    java.util.zip.ZipFile(temp.toFile()).use { zip ->
                        require(zip.getEntry("AndroidManifest.xml") != null) { "APK must contain AndroidManifest.xml" }
                    }
                } catch (_: java.util.zip.ZipException) { throw IllegalArgumentException("APK must be a valid ZIP archive") }
            } else {
                val probe = R8RetraceEngine(mappingLimit).retrace(temp,"java.lang.RuntimeException")
                require(probe.status==RetraceStatus.SUCCESS) { "Invalid R8 mapping" }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            writeTransaction { c ->
                // Check inside the same reservation used to publish the artifact and its reference.
                c.prepareStatement("SELECT id FROM builds WHERE id=?").use { p ->
                    p.setString(1,buildId); p.executeQuery().use { require(it.next()) { "Build not found" } }
                }
                val destination = artifact(hash)
                if(!Files.exists(destination)) {
                    try { Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: AtomicMoveNotSupportedException) { Files.move(temp,destination) }
                }
                val column = if(apk) "apk_hash" else "mapping_hash"
                c.prepareStatement("UPDATE builds SET $column=? WHERE id=?").use { p ->
                    p.setString(1,hash);p.setString(2,buildId);p.executeUpdate()
                }
            }
            pruneUnusedArtifacts()
            return getBuild(buildId)
        } finally { Files.deleteIfExists(temp) }
    }
    override fun deleteMapping(buildId: String) {
        require(execute("UPDATE builds SET mapping_hash=NULL WHERE id=?",buildId)==1) { "Build not found" }
        pruneUnusedArtifacts()
    }
    override fun deleteBuild(id: String) { require(execute("DELETE FROM builds WHERE id=?",id)==1) { "Build not found" }; pruneUnusedArtifacts() }
    override fun deleteProject(id: String) { require(execute("DELETE FROM projects WHERE id=?",id)==1) { "Project not found" }; pruneUnusedArtifacts() }
}

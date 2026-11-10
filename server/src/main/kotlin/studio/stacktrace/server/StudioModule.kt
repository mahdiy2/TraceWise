package studio.stacktrace.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.staticFiles
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import studio.stacktrace.*
import studio.stacktrace.storage.LocalStore
import studio.stacktrace.storage.WorkspaceRepository
import studio.stacktrace.retrace.R8RetraceEngine
import java.security.MessageDigest
import java.sql.SQLException

fun Application.studioModule(config: ServerConfig) {
    val logger = log
    val container = koinApplication { modules(module {
        single<WorkspaceRepository> { LocalStore(config.storage,config.mappingLimit,config.apkLimit) }
        single { R8RetraceEngine(config.mappingLimit) }
    }) }
    monitor.subscribe(ApplicationStopped) { container.close() }
    val store = container.koin.get<WorkspaceRepository>()
    val retrace = RetraceService(store,container.koin.get(),config.concurrency)
    install(ContentNegotiation) { json(apiJson) }
    install(CallLogging) { format { call -> "${call.request.httpMethod.value} ${call.response.status()?.value ?: 0}" } }
    install(StatusPages) {
        exception<ApiProblem> { call,e -> call.respond(e.status,ApiError(e.code,e.message)) }
        exception<SerializationException> { call,_ -> call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_JSON","Invalid JSON request")) }
        exception<IllegalArgumentException> { call,e -> call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_REQUEST",e.message ?: "Invalid request")) }
        exception<SQLException> { call,e ->
            if(e.errorCode == 19) call.respond(HttpStatusCode.Conflict,ApiError("CONFLICT","Duplicate application/build or invalid project reference"))
            else { logger.error("Database operation failed (code {})",e.errorCode); call.respond(HttpStatusCode.InternalServerError,ApiError("STORAGE_ERROR","Storage operation failed")) }
        }
        exception<Throwable> { call,e ->
            if(e is CancellationException) throw e
            logger.error("Request failed ({})",e.javaClass.simpleName)
            call.respond(HttpStatusCode.InternalServerError,ApiError("INTERNAL_ERROR","The operation could not be completed"))
        }
        status(HttpStatusCode.NotFound) { call,_ -> call.respond(HttpStatusCode.NotFound,ApiError("NOT_FOUND","Endpoint not found")) }
    }
    routing {
        get("/health") { call.respondText("ok") }
        route("/api/v1") {
            intercept(ApplicationCallPipeline.Plugins) {
                call.response.headers.append(HttpHeaders.CacheControl,"no-store")
                config.token?.let { expected ->
                    val header = call.request.headers[HttpHeaders.Authorization].orEmpty()
                    val received = if(header.startsWith("Bearer ")) header.removePrefix("Bearer ") else ""
                    if(!MessageDigest.isEqual(expected.toByteArray(),received.toByteArray())) {
                        call.response.headers.append(HttpHeaders.WWWAuthenticate,"Bearer")
                        call.respond(HttpStatusCode.Unauthorized,ApiError("UNAUTHORIZED","Valid bearer token required"))
                        finish()
                    }
                }
            }
            get("/dashboard") { call.respond(disk { store.dashboard() }) }
            get("/projects") { call.respond(disk { store.projects() }) }
            post("/projects") {
                val request = call.jsonBody<ProjectRequest>()
                call.respond(HttpStatusCode.Created,disk { store.createProject(request.name,request.applicationId,request.description) })
            }
            get("/projects/{projectId}") {
                val id = call.id("projectId")
                call.respond(disk { store.projects().find { it.id==id } ?: missing("Project") })
            }
            put("/projects/{projectId}") {
                val id = call.id("projectId"); val request = call.jsonBody<ProjectRequest>()
                call.respond(disk {
                    val project = store.projects().find { it.id==id } ?: missing("Project")
                    require(request.applicationId == project.applicationId) { "Application ID is immutable" }
                    store.updateProject(id,request.name,request.description)
                    store.projects().single { it.id==id }
                })
            }
            delete("/projects/{projectId}") {
                val id = call.id("projectId")
                disk { if(store.projects().none { it.id==id }) missing("Project"); store.deleteProject(id) }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/projects/{projectId}/builds") {
                val id = call.id("projectId")
                call.respond(disk { if(store.projects().none { it.id==id }) missing("Project"); store.builds(id) })
            }
            post("/projects/{projectId}/builds") {
                val id = call.id("projectId"); val request = call.jsonBody<BuildRequest>()
                call.respond(HttpStatusCode.Created,disk {
                    if(store.projects().none { it.id==id }) missing("Project")
                    store.createBuild(id,request.versionName,request.versionCode,request.variant)
                })
            }
            get("/builds/{buildId}") {
                val id=call.id("buildId"); call.respond(disk { findBuild(store,id) })
            }
            delete("/builds/{buildId}") {
                val id=call.id("buildId"); disk { findBuild(store,id); store.deleteBuild(id) }; call.respond(HttpStatusCode.NoContent)
            }
            post("/builds/{buildId}/mapping") { call.respond(receiveArtifact(call,store,config,false)) }
            post("/builds/{buildId}/apk") { call.respond(receiveArtifact(call,store,config,true)) }
            delete("/builds/{buildId}/mapping") {
                val id=call.id("buildId"); disk { findBuild(store,id); store.deleteMapping(id) }; call.respond(HttpStatusCode.NoContent)
            }
            post("/retrace") {
                val r = call.jsonBody<RetraceRequest>()
                val result = retrace.run(r.buildId,r.applicationId,r.projectId,r.versionCode,r.variant,listOf(r.stackTrace)).single()
                call.respond(if(result.success) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity,result)
            }
            post("/retrace/batch") {
                val r = call.jsonBody<BatchRetraceRequest>()
                call.respond(BatchRetraceResponse(retrace.run(r.buildId,r.applicationId,r.projectId,r.versionCode,r.variant,r.stackTraces)))
            }
        }
        config.webDirectory?.let { staticFiles("/",it.toFile(),index="index.html") }
    }
}
fun findBuild(store: WorkspaceRepository,id: String): ApplicationBuild = try { store.getBuild(id) } catch(_: IllegalArgumentException) { missing("Build") }

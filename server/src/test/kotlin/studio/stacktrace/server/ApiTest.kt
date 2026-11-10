package studio.stacktrace.server

import io.ktor.server.testing.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ApiTest {
    private fun scenario(limit: Long=50L*1024*1024, block: suspend ApplicationTestBuilder.() -> Unit) {
        val dir=Files.createTempDirectory("studio-api")
        try { testApplication {
            application { studioModule(ServerConfig(dir,"test-token",mappingLimit=limit)) }
            block()
        } } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private suspend fun ApplicationTestBuilder.postJson(path: String,json: String) = client.post(path) {
        bearerAuth("test-token"); contentType(ContentType.Application.Json); setBody(json)
    }
    private fun field(text: String,name: String) = Json.parseToJsonElement(text).jsonObject.getValue(name).jsonPrimitive.content
    private suspend fun ApplicationTestBuilder.createBuild(): String {
        val project=postJson("/api/v1/projects","""{"name":"Example","applicationId":"com.example.app"}""")
        assertEquals(HttpStatusCode.Created,project.status)
        val id=field(project.bodyAsText(),"id")
        val build=postJson("/api/v1/projects/$id/builds","""{"versionName":"1.0","versionCode":72}""")
        assertEquals(HttpStatusCode.Created,build.status)
        return field(build.bodyAsText(),"id")
    }
    private suspend fun ApplicationTestBuilder.upload(id: String, text: String) = client.post("/api/v1/builds/$id/mapping") {
        bearerAuth("test-token")
        setBody(MultiPartFormDataContent(formData {
            append("file",text.toByteArray(),Headers.build {
                append(HttpHeaders.ContentType,"text/plain")
                append(HttpHeaders.ContentDisposition,"filename=\"mapping.txt\"")
            })
        }))
    }
    @Test fun endToEndMappingSingleBatchAndOriginalPreservation() = scenario {
        val id=createBuild()
        val uploaded=upload(id,"com.example.Crash -> a:\n    1:1:void explode():42:42 -> b\n")
        assertEquals(HttpStatusCode.OK,uploaded.status,uploaded.bodyAsText())
        assertEquals(64,field(uploaded.bodyAsText(),"mappingSha256").length)
        val original="java.lang.RuntimeException\n\tat a.b(SourceFile:1)"
        val request=buildJsonObject {
            put("applicationId","com.example.app"); put("versionCode",72); put("stackTrace",original)
        }
        val response=postJson("/api/v1/retrace",request.toString())
        assertEquals(HttpStatusCode.OK,response.status,response.bodyAsText())
        val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(original,body.getValue("original").jsonPrimitive.content)
        assertContains(body.getValue("translated").jsonPrimitive.content,"com.example.Crash.explode(Crash.java:42)")
        val batch=postJson("/api/v1/retrace/batch",buildJsonObject {
            put("buildId",id); putJsonArray("stackTraces") { add(original); add("") }
        }.toString())
        assertEquals(HttpStatusCode.OK,batch.status,batch.bodyAsText())
        val results=Json.parseToJsonElement(batch.bodyAsText()).jsonObject.getValue("results").jsonArray
        assertEquals(2,results.size)
        assertEquals("EMPTY_TRACE",results[1].jsonObject.getValue("errorCode").jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains("studio-api"))
        val dashboard=client.get("/api/v1/dashboard") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK,dashboard.status)
        val summary=Json.parseToJsonElement(dashboard.bodyAsText()).jsonObject
        assertEquals(1,summary.getValue("projectCount").jsonPrimitive.int)
        assertEquals(2,summary.getValue("recentRetraces").jsonArray.size)
        assertFalse(dashboard.bodyAsText().contains(original))
    }
    @Test fun authenticationAndInvalidRequests() = scenario {
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/projects").status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/projects") { bearerAuth("wrong") }.status)
        assertEquals(HttpStatusCode.BadRequest,postJson("/api/v1/projects","{}").status)
        assertEquals(HttpStatusCode.BadRequest,postJson("/api/v1/projects","not json").status)
        assertEquals(HttpStatusCode.BadRequest,postJson("/api/v1/projects","""{"name":"App","applicationId":"../unsafe"}""").status)
        assertEquals(HttpStatusCode.BadRequest,postJson("/api/v1/retrace","""{"projectId":"invalid-id","versionCode":1,"stackTrace":"trace"}""").status)
        assertEquals(HttpStatusCode.BadRequest,postJson("/api/v1/retrace","""{"applicationId":"../unsafe","versionCode":1,"stackTrace":"trace"}""").status)
        val missing=postJson("/api/v1/retrace","""{"applicationId":"com.missing.app","versionCode":1,"stackTrace":"trace"}""")
        assertEquals(HttpStatusCode.NotFound,missing.status,missing.bodyAsText())
    }
    @Test fun missingMappingInvalidReplacementAndDeletion() = scenario {
        val id=createBuild()
        assertEquals(HttpStatusCode.Conflict,postJson("/api/v1/retrace","""{"buildId":"$id","stackTrace":"trace"}""").status)
        assertEquals(HttpStatusCode.BadRequest,upload(id,"not a mapping").status)
        assertEquals(HttpStatusCode.OK,upload(id,"com.example.Crash -> a:\n    void explode() -> b\n").status)
        val deleted=client.delete("/api/v1/builds/$id/mapping") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.NoContent,deleted.status)
        assertEquals(HttpStatusCode.Conflict,postJson("/api/v1/retrace","""{"buildId":"$id","stackTrace":"trace"}""").status)
    }
    @Test fun rejectsAmbiguityAndConflictingSelectors() = scenario {
        val id=createBuild()
        val metadata=client.get("/api/v1/builds/$id") { bearerAuth("test-token") }
        val projectId=field(metadata.bodyAsText(),"projectId")
        assertEquals(HttpStatusCode.Created,postJson("/api/v1/projects/$projectId/builds","""{"versionName":"1.0","versionCode":72,"variant":"debug"}""").status)
        assertEquals(HttpStatusCode.Conflict,postJson("/api/v1/retrace","""{"applicationId":"com.example.app","versionCode":72,"stackTrace":"trace"}""").status)
        assertEquals(HttpStatusCode.BadRequest,postJson("/api/v1/retrace","""{"buildId":"$id","applicationId":"com.example.app","stackTrace":"trace"}""").status)
        assertEquals(HttpStatusCode.BadRequest,postJson("/api/v1/retrace/batch","""{"buildId":"$id","stackTraces":[]}""").status)
    }
    @Test fun projectUpdateConflictAndCascadeDeletion() = scenario {
        val id=createBuild()
        val metadata=client.get("/api/v1/builds/$id") { bearerAuth("test-token") }
        val projectId=field(metadata.bodyAsText(),"projectId")
        assertEquals(HttpStatusCode.Conflict,postJson("/api/v1/projects","""{"name":"duplicate","applicationId":"com.example.app"}""").status)
        val edited=client.put("/api/v1/projects/$projectId") {
            bearerAuth("test-token"); contentType(ContentType.Application.Json)
            setBody("""{"name":"Renamed","applicationId":"com.example.app","description":"Updated"}""")
        }
        assertEquals(HttpStatusCode.OK,edited.status,edited.bodyAsText())
        assertEquals("Renamed",field(edited.bodyAsText(),"name"))
        assertEquals(HttpStatusCode.NoContent,client.delete("/api/v1/projects/$projectId") { bearerAuth("test-token") }.status)
        assertEquals(HttpStatusCode.NotFound,client.get("/api/v1/builds/$id") { bearerAuth("test-token") }.status)
    }
    @Test fun uploadLimits() = scenario(limit=32) {
        val id=createBuild()
        assertEquals(HttpStatusCode.PayloadTooLarge,upload(id,"x".repeat(100)).status)
    }
    @Test fun rejectsWebDirectoryContainingPrivateStorage() {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig(java.nio.file.Path.of("public/data"),"token",webDirectory=java.nio.file.Path.of("public"))
        }
    }
    @Test fun authRequiredUnlessExplicitDevelopment() {
        assertFailsWith<IllegalArgumentException> { ServerConfig(java.nio.file.Path.of("data"),null) }
    }
}

package studio.stacktrace.server

import io.ktor.utils.io.*

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.stacktrace.ApplicationBuild
import studio.stacktrace.storage.WorkspaceRepository
import java.nio.file.Files

suspend fun receiveArtifact(call: ApplicationCall,store: WorkspaceRepository,config: ServerConfig,apk: Boolean): ApplicationBuild {
    val id = call.id("buildId")
    disk { findBuild(store,id) }
    if(call.request.contentType().withoutParameters()!=ContentType.MultiPart.FormData)
        throw ApiProblem(HttpStatusCode.UnsupportedMediaType,"CONTENT_TYPE","Use multipart/form-data with one file field")
    val limit = if(apk) config.apkLimit else config.mappingLimit
    if((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0)>limit+65536)
        throw ApiProblem(HttpStatusCode.PayloadTooLarge,"UPLOAD_TOO_LARGE","Upload exceeds configured limit")
    val temp = disk { Files.createDirectories(config.storage.resolve("uploads")); Files.createTempFile(config.storage.resolve("uploads"),"upload-",if(apk) ".apk" else ".txt") }
    try {
        var files = 0
        val multipart = call.receiveMultipart(formFieldLimit=limit+65536)
        multipart.forEachPart { part ->
            try {
                require(part is PartData.FileItem && part.name=="file") { "Submit exactly one file field" }
                require(++files==1) { "Submit exactly one file" }
                require(part.originalFileName.orEmpty().endsWith(if(apk) ".apk" else ".txt",true)) { "Unsupported file extension" }
                val type = part.contentType?.withoutParameters()?.toString()
                val types = if(apk) setOf("application/vnd.android.package-archive","application/zip","application/octet-stream") else setOf("text/plain","application/octet-stream")
                require(type==null || type in types) { "Unsupported file content type" }
                withContext(Dispatchers.IO) {
                    Files.newOutputStream(temp).use { output ->
                        val channel = part.provider(); val buffer = ByteArray(65536); var total=0L
                        while(true) {
                            val n=channel.readAvailable(buffer,0,buffer.size); if(n<0) break
                            total+=n
                            if(total>limit) { channel.cancel(null); throw ApiProblem(HttpStatusCode.PayloadTooLarge,"UPLOAD_TOO_LARGE","Upload exceeds configured limit") }
                            output.write(buffer,0,n)
                        }
                    }
                }
            } finally { part.dispose() }
        }
        require(files==1) { "File is required" }
        return disk { store.upload(id,temp,apk) }
    } finally { withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(temp) } }
}

package studio.stacktrace.server

import io.ktor.utils.io.*

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.UUID

class ApiProblem(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)
fun missing(what: String): Nothing = throw ApiProblem(HttpStatusCode.NotFound,"NOT_FOUND","$what not found")
fun validateId(value: String): String {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "Invalid identifier" }
    return value
}
fun ApplicationCall.id(name: String): String = validateId(parameters[name] ?: throw IllegalArgumentException("Missing identifier"))
suspend fun <T> disk(block: () -> T): T = withContext(Dispatchers.IO) { block() }
val apiJson = Json { encodeDefaults=true; ignoreUnknownKeys=false }
suspend inline fun <reified T> ApplicationCall.jsonBody(): T {
    if(request.contentType().withoutParameters()!=ContentType.Application.Json) throw ApiProblem(HttpStatusCode.UnsupportedMediaType,"CONTENT_TYPE","Use application/json")
    val limit = 12L*1024*1024
    if((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0)>limit) throw ApiProblem(HttpStatusCode.PayloadTooLarge,"BODY_TOO_LARGE","JSON request exceeds 12 MiB")
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16384)
    while(true) {
        val count = channel.readAvailable(buffer,0,buffer.size)
        if(count<0) break
        if(output.size().toLong()+count>limit) {
            channel.cancel(null)
            throw ApiProblem(HttpStatusCode.PayloadTooLarge,"BODY_TOO_LARGE","JSON request exceeds 12 MiB")
        }
        output.write(buffer,0,count)
    }
    return apiJson.decodeFromString<T>(output.toString(Charsets.UTF_8))
}

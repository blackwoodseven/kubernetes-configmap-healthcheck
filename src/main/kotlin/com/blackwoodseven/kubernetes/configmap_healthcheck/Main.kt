package com.blackwoodseven.kubernetes.configmap_healthcheck

import mu.KotlinLogging
import org.jetbrains.ktor.application.ApplicationCall
import org.jetbrains.ktor.application.call
import org.jetbrains.ktor.host.embeddedServer
import org.jetbrains.ktor.http.HttpStatusCode
import org.jetbrains.ktor.netty.Netty
import org.jetbrains.ktor.response.respondText
import org.jetbrains.ktor.routing.get
import org.jetbrains.ktor.routing.routing
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private val logger = KotlinLogging.logger {}

var fileSystem = FileSystems.getDefault()

data class Config(
        val paths: List<Path>,
        val port: Int
)

fun parseConfig(): Config {
    val volumesString: String = System.getenv("VOLUMES") ?: throw RuntimeException("No volumes specified in the VOLUMES environment variable.")
    val volumeStrings = volumesString.split(',')
    val volumes = volumeStrings.map { fileSystem.getPath(it) }
    return Config(
            volumes,
            if (System.getenv("PORT") != null) System.getenv("PORT").toInt() else 6852
    )
}

fun checkVolumes(paths: List<Path>) {
    val isNotAbsolute = paths.filter { !it.isAbsolute }
    if (isNotAbsolute.isNotEmpty()) {
        throw RuntimeException("The following paths are not absolute: $isNotAbsolute")
    }
    val nonExistentPaths = paths.filter { !Files.exists(it) }
    if (nonExistentPaths.isNotEmpty()) {
        throw RuntimeException("The following paths does not exist: $nonExistentPaths")
    }
    val pathsAreNotDirectories = paths.filter { !Files.isDirectory(it) }
    if (pathsAreNotDirectories.isNotEmpty()) {
        throw RuntimeException("The following paths are not directories: $pathsAreNotDirectories")
    }
    val isNotAVolume = paths.filter { !Files.exists(it.resolve("..data")) }
    if (isNotAVolume.isNotEmpty()) {
        throw RuntimeException("The following paths does not seem to be volumes: $isNotAVolume")
    }
}

suspend fun modifiedResponse(volumes: Map<Path, FileTime>, call: ApplicationCall) {
    val anyModified = volumes.any { (path, originalTimeStamp) ->
        hasVolumeBeenModified(path, originalTimeStamp)
    }

    if (anyModified) {
        call.respond(HttpStatusCode.InternalServerError)
    } else {
        call.respondText("Volume has not been modified")
    }
}

fun main(args : Array<String>) {
    val config = parseConfig()
    checkVolumes(config.paths)

    val volumeTimestamps = resolveVolumeTimestamps(config.paths)

    val server = embeddedServer(Netty, config.port) {
        routing {
            get("/") {
                modifiedResponse(volumeTimestamps, call)
            }

            get("/health") {
                modifiedResponse(volumeTimestamps, call)
            }

            get("/healthz") {
                modifiedResponse(volumeTimestamps, call)
            }
        }
    }
    server.start(true)
}

fun hasVolumeBeenModified(volumePath: Path, timestampToCheckAgainst: FileTime): Boolean {
    val lastModified = Files.getLastModifiedTime(volumePath.resolve("..data"))
    return lastModified != timestampToCheckAgainst
}

fun resolveVolumeTimestamps(paths: List<Path>): Map<Path, FileTime> {
    return paths.map { it to Files.getLastModifiedTime(it.resolve("..data")) }.toMap()
}

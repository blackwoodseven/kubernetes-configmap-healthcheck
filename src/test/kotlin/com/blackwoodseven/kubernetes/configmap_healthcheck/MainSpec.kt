package com.blackwoodseven.kubernetes.configmap_healthcheck

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.has
import com.natpryce.hamkrest.throws
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant


class MainSpec : Spek({
    describe("the system should be sane") {
        it("should calculate correctly") {
            assert.that(2 + 2, equalTo(4))
        }
    }

    describe("parseConfig") {
        it("should parse the configuration from the environment variables.") {
            setEnvironment(mapOf(
                    "VOLUMES" to "/some/volume",
                    "PORT" to "8000"
            ))

            val config = parseConfig()

            assert.that(config, equalTo(Config(
                    listOf(fileSystem.getPath("/some/volume")),
                    8000
            )))
        }

        it("should multiple volumes if given.") {
            setEnvironment(mapOf(
                    "VOLUMES" to "/some/volume,/some/other-volume",
                    "PORT" to "8000"
            ))

            val config = parseConfig()

            assert.that(config, equalTo(Config(
                    listOf(
                            fileSystem.getPath("/some/volume"),
                            fileSystem.getPath("/some/other-volume")
                    ),
                    8000
            )))
        }

        it("should resolve to port 6852 is not specified") {
            setEnvironment(mapOf(
                    "VOLUMES" to "/some/volume"
            ))

            val config = parseConfig()

            assert.that(config, equalTo(Config(
                    listOf(fileSystem.getPath("/some/volume")),
                    6852
            )))
        }

        it("should fail if no paths are given") {
            setEnvironment(emptyMap())

            assert.that({parseConfig()}, throws<RuntimeException>())
        }
    }

    describe("volume stuff") {
        var fileSystem = Jimfs.newFileSystem(Configuration.unix())
        var volumePath = fileSystem.getPath("/mnt/some-volume")
        beforeEachTest {
            fileSystem = Jimfs.newFileSystem(Configuration.unix())
            volumePath = fileSystem.getPath("/mnt/some-volume")
            Files.createDirectories(volumePath)
            val symlinkPath = fileSystem.getPath("/mnt/some-volume/..data")
            val targetPath = fileSystem.getPath("/mnt/some-volume/somerandomid")
            Files.createDirectory(targetPath)
            Files.createSymbolicLink(symlinkPath, targetPath)
            Files.setLastModifiedTime(symlinkPath, FileTime.from(Instant.parse("2017-07-12T11:12:40.00Z")))
        }

        describe("checkVolumes") {
            it("should throw an exception if the paths are not absolute") {
                assert.that({
                    checkVolumes(listOf(
                            fileSystem.getPath("non-absolute-path")
                    ))
                }, throws<RuntimeException>(has(Exception::message, equalTo("The following paths are not absolute: [non-absolute-path]"))))
            }

            it("should throw an exception if the paths do not exist") {
                assert.that({
                    checkVolumes(listOf(
                            fileSystem.getPath("/mnt/non-existent")
                    ))
                }, throws<RuntimeException>(has(Exception::message, equalTo("The following paths does not exist: [/mnt/non-existent]"))))
            }

            it("should throw an exception if the paths are files") {
                val filePath = fileSystem.getPath("/mnt/this-is-a-file")
                Files.createFile(filePath)
                assert.that({
                    checkVolumes(listOf(
                            filePath
                    ))
                }, throws<RuntimeException>(has(Exception::message, equalTo("The following paths are not directories: [/mnt/this-is-a-file]"))))
            }

            it("should throw an exception if the paths are not volumes") {
                val filePath = fileSystem.getPath("/mnt/this-is-an-empty-directory")
                Files.createDirectory(filePath)
                assert.that({
                    checkVolumes(listOf(
                            filePath
                    ))
                }, throws<RuntimeException>(has(Exception::message, equalTo("The following paths does not seem to be volumes: [/mnt/this-is-an-empty-directory]"))))
            }
        }

        describe("hasVolumeBeenModified") {
            it("should return false if the volume has not been updated.") {
                assert.that(hasVolumeBeenModified(volumePath, FileTime.from(Instant.parse("2017-07-12T11:12:40.00Z"))), equalTo(false))
            }

            it("should return true if the volume has been updated.") {
                assert.that(hasVolumeBeenModified(volumePath, FileTime.from(Instant.parse("2017-07-13T11:12:40.00Z"))), equalTo(true))
            }
        }

        describe("resolveVolumeTimestamps") {
            it("should resolve all volume paths into a path -> modification map") {
                val volumes = listOf(
                        volumePath
                )
                val resolvedTimestamps = resolveVolumeTimestamps(volumes)

                assert.that(resolvedTimestamps, equalTo(mapOf(volumePath to FileTime.from(Instant.parse("2017-07-12T11:12:40.00Z")))))
            }

            it("should fail is asked for a non-existent path") {
                val volumes = listOf(
                        fileSystem.getPath("/mnt/does-not-exist")
                )
                assert.that({resolveVolumeTimestamps(volumes)}, throws<java.nio.file.NoSuchFileException>())
            }
        }
    }
})

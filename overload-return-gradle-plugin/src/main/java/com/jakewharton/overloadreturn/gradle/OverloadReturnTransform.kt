package com.jakewharton.overloadreturn.gradle

import com.android.build.api.transform.Format.DIRECTORY
import com.android.build.api.transform.Format.JAR
import com.android.build.api.transform.QualifiedContent.DefaultContentType.CLASSES
import com.android.build.api.transform.QualifiedContent.Scope.PROJECT
import com.android.build.api.transform.Status.ADDED
import com.android.build.api.transform.Status.CHANGED
import com.android.build.api.transform.Status.REMOVED
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInvocation
import com.jakewharton.overloadreturn.compiler.OverloadReturnCompiler
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class OverloadReturnTransform : Transform() {
  override fun getName() = "overloadReturn"
  override fun isIncremental() = true
  override fun getScopes() = mutableSetOf(PROJECT)
  override fun getInputTypes() = setOf(CLASSES)

  // Always enable debug logging since System.out is redirected to Gradle's QUIET log level.
  private val compiler = OverloadReturnCompiler(debug = true)

  override fun transform(invocation: TransformInvocation) {
    val outputProvider = invocation.outputProvider

    if (!invocation.isIncremental) {
      outputProvider.deleteAll()
    }

    invocation.inputs.forEach { input ->
      input.directoryInputs.forEach { directory ->
        val outputFileRoot = outputProvider.getContentLocation(directory.name, setOf(CLASSES),
            mutableSetOf(PROJECT), DIRECTORY)
        System.err.println("DIRECTORY: ${directory.file} changed: ${directory.changedFiles.size} incremental: ${invocation.isIncremental}")

        if (!invocation.isIncremental) {
          processPath(directory.file.toPath(), outputFileRoot.toPath())
        } else {
          directory.changedFiles.forEach { file, status ->
            val relativeFile = file.relativeTo(directory.file)
            val outputFile = outputFileRoot.resolve(relativeFile)
            when (status) {
              ADDED, CHANGED -> {
                val inputBytes = file.readBytes()
                val outputBytes = compiler.parse(inputBytes).toBytes()
                outputFile.writeBytes(outputBytes)
              }
              REMOVED -> {
                outputFile.delete()
              }
            }
          }
        }
      }

      input.jarInputs.forEach { jar ->
        val outputJar = outputProvider.getContentLocation(jar.name, setOf(CLASSES),
            mutableSetOf(PROJECT), JAR)

        System.err.println("JAR: ${jar.status} ${jar.file} $outputJar incremental: ${invocation.isIncremental}")

        when (jar.status) {
          ADDED, CHANGED -> {
            outputJar.delete()
            val outputUri = URI("jar:file", outputJar.toURI().path, null)
            FileSystems.newFileSystem(outputUri, mapOf("create" to "true"), null).use { outputFs ->
              FileSystems.newFileSystem(jar.file.toPath(), null).use { inputFs ->
                val inputRoot = inputFs.rootDirectories.single()
                val outputRoot = outputFs.rootDirectories.single()
                processPath(inputRoot, outputRoot)
              }
            }
          }
          REMOVED -> {
            outputJar.delete()
          }
        }
      }
    }
  }

  private fun processPath(inputRoot: Path, outputRoot: Path) {
    Files.walkFileTree(inputRoot, object : SimpleFileVisitor<Path>() {
      override fun visitFile(inputPath: Path,
          attrs: BasicFileAttributes): FileVisitResult {
        val relativePath = inputRoot.relativize(inputPath)
        val outputPath = outputRoot.resolve(relativePath)
        Files.createDirectories(outputPath.parent)

        if (inputPath.toString().endsWith(".class")) {
          val inputBytes = Files.readAllBytes(inputPath)
          val outputBytes = compiler.parse(inputBytes).toBytes()
          Files.write(outputPath, outputBytes)
        } else {
          Files.copy(inputPath, outputPath)
        }
        return CONTINUE
      }
    })
  }
}

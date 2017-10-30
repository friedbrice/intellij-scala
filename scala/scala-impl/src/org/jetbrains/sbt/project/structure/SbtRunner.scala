package org.jetbrains.sbt
package project.structure

import java.io.{FileNotFoundException, _}
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.event._
import com.intellij.openapi.externalSystem.model.task.{ExternalSystemTaskId, ExternalSystemTaskNotificationEvent, ExternalSystemTaskNotificationListener}
import org.jetbrains.plugins.scala.project.Version
import org.jetbrains.sbt.SbtUtil._
import org.jetbrains.sbt.project.structure.SbtRunner._
import org.jetbrains.sbt.shell.SbtShellCommunication
import org.jetbrains.sbt.shell.SbtShellCommunication.{EventAggregator, Output, TaskComplete, TaskStart}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Try}
import scala.xml.{Elem, XML}

/**
 * @author Pavel Fatin
 */
class SbtRunner(vmExecutable: File, vmOptions: Seq[String], environment: Map[String, String],
                customLauncher: Option[File], customStructureJar: Option[File],
                id: ExternalSystemTaskId,
                listener: ExternalSystemTaskNotificationListener) {
  private val LauncherDir = getSbtLauncherDir
  private val SbtLauncher = customLauncher.getOrElse(getDefaultLauncher)
  private def sbtStructureJar(sbtVersion: String) = customStructureJar.getOrElse(LauncherDir / s"sbt-structure-$sbtVersion.jar")

  private val cancellationFlag: AtomicBoolean = new AtomicBoolean(false)

  def cancel(): Unit =
    cancellationFlag.set(true)

  def read(directory: File, options: Seq[String], importFromShell: Boolean): Try[(Elem, String)] = {

    if (SbtLauncher.exists()) {

      val sbtVersion = Version(detectSbtVersion(directory, SbtLauncher))
      val majorSbtVersion = binaryVersion(sbtVersion)
      lazy val project = id.findProject()
      // if the project is being freshly imported, there is no project instance to get the shell component
      val useShellImport = importFromShell && shellImportSupported(sbtVersion) && project != null

      if (importSupported(sbtVersion)) usingTempFile("sbt-structure", Some(".xml")) { structureFile =>

        val structureFilePath = path(structureFile)

        val messageResult: Try[String] = {
          if (useShellImport) {
            val shell = SbtShellCommunication.forProject(project)
            dumpFromShell(directory, shell, structureFilePath, options)
          }
          else dumpFromProcess(directory, structureFilePath, options: Seq[String], majorSbtVersion)
        }

        messageResult.flatMap { messages =>
          if (structureFile.length > 0) Try {
            val elem = XML.load(structureFile.toURI.toURL)
            (elem, messages)
          }
          else Failure(SbtException.fromSbtLog(messages))
        }

      } else {
        val message = s"sbt $sinceSbtVersion+ required. Please update project build.properties."
        Failure(new UnsupportedOperationException(message))
      }
    }
    else {
      val error = s"sbt launcher not found at ${SbtLauncher.getCanonicalPath}"
      Failure(new FileNotFoundException(error))
    }
  }

  private val statusUpdate = (message:String) =>
    listener.onStatusChange(new ExternalSystemTaskNotificationEvent(id, message.trim))

  private def dumpFromShell(dir: File, shell: SbtShellCommunication, structureFilePath: String, options: Seq[String]): Try[String] = {

    listener.onStart(id, dir.getCanonicalPath)

    val optString = options.mkString(" ")
    val setCmd = s"""set _root_.org.jetbrains.sbt.StructureKeys.sbtStructureOptions in Global := "$optString""""
    val cmd = s";reload; $setCmd ;*/*:dumpStructureTo $structureFilePath"

    val taskDescriptor = new TaskOperationDescriptorImpl("project structure dump", System.currentTimeMillis(), "project-structure-dump")
    val aggregator = esMessageAggregator(id, s"dump:${UUID.randomUUID()}", taskDescriptor, shell, listener)
    val output = shell.command(cmd, new StringBuilder, aggregator, showShell = false)

    Await.ready(output, Duration.Inf)

    output.value.get.map(_.toString())
  }

  /** Aggregates (messages, warnings) and updates external system listener. */
  private def messageAggregator(statusUpdate: String=>Unit): EventAggregator[StringBuilder] = {
    case (m,TaskStart) => m
    case (m,TaskComplete) => m
    case (messages, Output(message)) =>
      val text = message.trim
      if (text.nonEmpty) statusUpdate(text)
      messages.append(System.lineSeparator).append(text)
  }

  private def esMessageAggregator(id: ExternalSystemTaskId,
                                  dumpTaskId: String,
                                  taskDescriptor: TaskOperationDescriptor,
                                  shell: SbtShellCommunication,
                                  notifications: ExternalSystemTaskNotificationListener): EventAggregator[StringBuilder] = {
    case (messages, TaskStart) =>
      val startEvent = new ExternalSystemStartEventImpl[TaskOperationDescriptor](dumpTaskId, null, taskDescriptor)
      val event = new ExternalSystemTaskExecutionEvent(id, startEvent)
      notifications.onStatusChange(event)
      messages
    case (messages, TaskComplete) =>
      val endEvent = new ExternalSystemFinishEventImpl[TaskOperationDescriptor](
        dumpTaskId, null, taskDescriptor,
        new SuccessResultImpl(0, System.currentTimeMillis(), true)
      )
      val event = new ExternalSystemTaskExecutionEvent(id, endEvent)
      notifications.onStatusChange(event)
      messages
    case (messages, Output(text)) =>
      if (text.contains("(i)gnore")) {
        val ex = new ExternalSystemException("Error importing sbt project. Please check sbt shell output.")
        shell.send("i" + System.lineSeparator)
        listener.onFailure(id, ex)
      } else {

        val progressEvent = new ExternalSystemStatusEventImpl[TaskOperationDescriptor](
          dumpTaskId, null, taskDescriptor, messages.size, -1, "lines"
        )
        val event = new ExternalSystemTaskExecutionEvent(id, progressEvent)

        notifications.onStatusChange(event)
        notifications.onTaskOutput(id, text, true)
      }
      messages.append(System.lineSeparator).append(text.trim)
  }

  private def shellImportSupported(sbtVersion: Version): Boolean =
    sbtVersion >= sinceSbtVersionShell

  private def importSupported(sbtVersion: Version): Boolean =
    sbtVersion >= sinceSbtVersion

  private def dumpFromProcess(directory: File, structureFilePath: String, options: Seq[String], sbtVersion: Version): Try[String] = {

    val optString = options.mkString(", ")
    val pluginJar = sbtStructureJar(sbtVersion.major(2).presentation)

    val setCommands = Seq(
      s"""shellPrompt := { _ => "" }""",
      s"""SettingKey[_root_.scala.Option[_root_.sbt.File]]("sbtStructureOutputFile") in _root_.sbt.Global := _root_.scala.Some(_root_.sbt.file("$structureFilePath"))""",
      s"""SettingKey[_root_.java.lang.String]("sbtStructureOptions") in _root_.sbt.Global := "$optString""""
    ).mkString("set _root_.scala.collection.Seq(", ",", ")")

    val sbtCommands = Seq(
      setCommands,
      s"""apply -cp "${path(pluginJar)}" org.jetbrains.sbt.CreateTasks""",
      s"*/*:dumpStructure"
    ).mkString(";",";","")

    val processCommandsRaw =
      path(vmExecutable) +:
        "-Djline.terminal=jline.UnsupportedTerminal" +:
        "-Dsbt.log.noformat=true" +:
        "-Dfile.encoding=UTF-8" +:
        (vmOptions ++ SbtOpts.loadFrom(directory)) :+
        "-jar" :+
        path(SbtLauncher)

    val processCommands = processCommandsRaw.filterNot(_.isEmpty)

    Try {
      val processBuilder = new ProcessBuilder(processCommands.asJava)
      processBuilder.directory(directory)
      processBuilder.environment().putAll(environment.asJava)
      val process = processBuilder.start()
      val result = using(new PrintWriter(new BufferedWriter(new OutputStreamWriter(process.getOutputStream, "UTF-8")))) { writer =>
        writer.println(sbtCommands)
        // exit needs to be in a separate command, otherwise it will never execute when a previous command in the chain errors
        writer.println("exit")
        writer.flush()
        handle(process, statusUpdate)
      }
      result.getOrElse("no output from sbt shell process available")
    }.orElse(Failure(SbtRunner.ImportCancelledException))
  }

  private def handle(process: Process, statusUpdate: String=>Unit): Try[String] = {
    val output = StringBuilder.newBuilder

    def update(textRaw: String): Unit = {
      val text = textRaw.trim
      output.append(System.lineSeparator).append(text)
      if (text.nonEmpty) statusUpdate(text)
    }

    val processListener: (OutputType, String) => Unit = {
      case (OutputType.StdOut, text) =>
        if (text.contains("(q)uit")) {
          val writer = new PrintWriter(process.getOutputStream)
          writer.println("q")
          writer.close()
        } else {
          update(text)
        }
      case (OutputType.StdErr, text) =>
        update(text)
    }

    Try {
      val handler = new OSProcessHandler(process, "sbt import", Charset.forName("UTF-8"))
      handler.addProcessListener(new ListenerAdapter(processListener))
      handler.startNotify()

      var processEnded = false
      while (!processEnded && !cancellationFlag.get())
        processEnded = handler.waitFor(SBT_PROCESS_CHECK_TIMEOUT_MSEC)

      if (!processEnded) {
        // task was cancelled
        handler.setShouldDestroyProcessRecursively(false)
        handler.destroyProcess()
        throw ImportCancelledException
      } else output.toString()
    }
  }

  private def path(file: File): String = file.getAbsolutePath.replace('\\', '/')
}

object SbtRunner {
  case object ImportCancelledException extends Exception

  val isInTest: Boolean = ApplicationManager.getApplication.isUnitTestMode

  val SBT_PROCESS_CHECK_TIMEOUT_MSEC = 100

  def getSbtLauncherDir: File = {
    val file: File = jarWith[this.type]
    val deep = if (file.getName == "classes") 1 else 2
    val res = (file << deep) / "launcher"
    if (!res.exists() && isInTest) {
      val start = jarWith[this.type].parent
      start.flatMap(findLauncherDir)
        .getOrElse(throw new RuntimeException(s"could not find sbt launcher dir at or above ${start.get}"))
    }
    else res
  }

  def getDefaultLauncher: File = getSbtLauncherDir / "sbt-launch.jar"

  private def findLauncherDir(from: File): Option[File] = {
    val launcherDir = from / "target" / "plugin" / "Scala" / "launcher"
    if (launcherDir.exists) Option(launcherDir)
    else from.parent.flatMap(findLauncherDir)
  }

  private val sinceSbtVersion = Version("0.12.4")
  val sinceSbtVersionShell = Version("0.13.5")

}
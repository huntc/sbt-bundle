package com.typesafe.sbt.bundle

import java.io.{ FileInputStream, BufferedInputStream }
import java.nio.charset.Charset
import java.security.MessageDigest

import com.typesafe.sbt.SbtNativePackager
import com.typesafe.sbt.packager.Stager
import com.typesafe.sbt.packager.universal.Archives
import sbt._
import sbt.Keys._
import SbtNativePackager.Universal

import scala.annotation.tailrec

object Import {

  case class Endpoint(protocol: String, bindPort: Int)

  object BundleKeys {

    val bundleConf = TaskKey[String](
      "bundle-conf",
      "The bundle configuration file contents"
    )

    val system = SettingKey[String](
      "bundle-system",
      "A logical name that can be used to associate multiple bundles with each other. This could be an application or service association and should include a version e.g. myapp-1.0.0."
    )

    val startStatusCommand = SettingKey[String](
      "bundle-start-status-command",
      "A command to be executed to check the start status; by default `exit 0` is used"
    )

    val bundleType = SettingKey[Configuration](
      "bundle-type",
      "The type of configuration that this bundling relates to. By default Universal is used."
    )

    val startCommand = SettingKey[Seq[String]](
      "bundle-start-command",
      "Command line args required to start the component. Paths are expressed relative to the component's bin folder. The default is to use the bash script in the bin folder."
    )

    val endpoints = SettingKey[Map[String, Endpoint]](
      "bundle-endpoints",
      """Declares endpoints. The default is Map("web" -> Endpoint("http", 9000))"""
    )
  }

  val Bundle = config("bundle") extend Universal
}

object SbtBundle extends AutoPlugin {

  import Import._
  import BundleKeys._
  import SbtNativePackager.autoImport._

  val autoImport = Import

  private val sha256 = "SHA-256"

  private val utf8 = "UTF-8"

  private val utf8Charset = Charset.forName(utf8)

  override def `requires` = SbtNativePackager

  override def trigger = AllRequirements

  override def projectSettings = Seq(
    bundleConf := getConfig.value,
    system := (packageName in Universal).value,
    startStatusCommand := "exit 0",
    bundleType := Universal,
    startCommand := Seq((file("bin") / (executableScriptName in Universal).value).getPath),
    endpoints := Map("web" -> Endpoint("http", 9000)),
    NativePackagerKeys.stage in Bundle := Def.taskDyn {
      Def.task {
        stageBundle(bundleType.value)
      }.value
    }.value,
    NativePackagerKeys.dist in Bundle := Def.taskDyn {
      Def.task {
        createDist((NativePackagerKeys.stage in Bundle).value)
      }.value
    }.value,
    NativePackagerKeys.stagingDirectory in Bundle := (target in Bundle).value / "stage",
    target in Bundle := target.value / "typesafe-conductr"
  )

  private def stageBundle(bundleTypeConfig: Configuration): Def.Initialize[Task[File]] = Def.task {
    val bundleConfTarget = (target in Bundle).value
    val bundleConfFile = writeConfig(bundleConfTarget, bundleConf.value)
    val componentDir = (packageName in Universal).value + java.io.File.separator
    val bundleMappings = (mappings in bundleTypeConfig).value.map(m => m._1 -> (componentDir + m._2)) ++
      bundleConfFile.pair(relativeTo(bundleConfTarget))
    val bundleName = (packageName in Universal).value
    val stageDir = (NativePackagerKeys.stagingDirectory in Bundle).value
    Stager.stage(Bundle.name)(streams.value, stageDir / bundleName, bundleMappings).getParentFile
  }

  private def createDist(stageDir: File): Def.Initialize[Task[File]] = Def.task {
    val bundleName = (packageName in Universal).value
    val bundleTarget = (target in Bundle).value
    val tgz = bundleTarget / (bundleName + ".tgz")
    Process(Seq("tar", "-pvc", bundleName), Some(stageDir)) #| Process(Seq("gzip", "-n")) #> tgz ! match {
      case 0 => ()
      case n => sys.error("Error tarballing " + tgz + ". Exit code: " + n)
    }
    val tgzName = tgz.getName
    val exti = tgzName.lastIndexOf('.')
    val hash = Hash.toHex(digestFile(tgz))
    val hashName = tgzName.take(exti) + "-" + hash + tgzName.drop(exti)
    val hashTgz = bundleTarget / hashName
    IO.move(tgz, hashTgz)
    hashTgz
  }

  private def digestFile(f: File): Array[Byte] = {
    val digest = MessageDigest.getInstance(sha256)
    val in = new BufferedInputStream(new FileInputStream(f))
    val buf = Array.ofDim[Byte](8192)
    try {
      @tailrec
      def readAndUpdate(r: Int): Unit =
        if (r != -1) {
          digest.update(buf, 0, r)
          readAndUpdate(in.read(buf))
        }
      readAndUpdate(in.read(buf))
      digest.digest
    } finally {
      in.close()
    }
  }

  private def formatSeq(strings: Seq[String]): String =
    strings.map(s => s""""$s"""").mkString("[", ", ", "]")

  private def formatEndpoints(endpoints: Map[String, Endpoint]): String = {
    val formatted =
      for {
        (label, Endpoint(protocol, bindPort)) <- endpoints
      } yield s"""|      "$label" = {
                  |        protocol  = "$protocol"
                  |        bind-port = $bindPort
                  |      }""".stripMargin
    formatted.mkString(f"{%n", f",%n", f"%n    }")
  }

  private def getConfig: Def.Initialize[Task[String]] = Def.task {
    s"""|version              = "1.0.0"
        |system               = "${system.value}"
        |start-status-command = "${startStatusCommand.value}"
        |components = {
        |  "${(packageName in Universal).value}" = {
        |    description      = "${projectInfo.value.description}"
        |    file-system-type = "${bundleType.value}"
        |    start-command    = ${formatSeq(startCommand.value)}
        |    endpoints        = ${formatEndpoints(endpoints.value)}
        |  }
        |}
        |""".stripMargin
  }

  private def writeConfig(target: File, contents: String): File = {
    val configFile = target / "bundle.conf"
    IO.write(configFile, contents, utf8Charset)
    configFile
  }
}

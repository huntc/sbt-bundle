lazy val root = (project in file(".")).enablePlugins(SbtNativePackager)

name := "simple-test"

version := "0.1.0-SNAPSHOT"

val checkBundleConf = taskKey[Unit]("check-main-css-contents")

checkBundleConf := {
  val contents = IO.read(target.value / "reactive-runtime" / "tmp" / "bundle.conf")
  val expectedContents = """version = "1.0.0"
                           |application = "simple-test-0.1.0-SNAPSHOT"
                           |components = {
                           |  "simple-test-0.1.0-SNAPSHOT" = {
                           |    description      = "simple-test"
                           |    file-system-type = "universal"
                           |    start-command    = ["bin/simple-test"]
                           |    endpoints        = {web = ["http://0.0.0.0:9000","http://0.0.0.0:9000"]}
                           |  }
                           |}""".stripMargin
  if (!contents.contains(expectedContents)) sys.error(s"Not what we expected: $contents")
}

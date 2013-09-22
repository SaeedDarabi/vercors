package semper.silicon

import java.nio.file.Path
import semper.sil.testing.DefaultSilSuite
import semper.sil.verifier.Verifier
import semper.sil.frontend.{Frontend, SilFrontend}

class SiliconTests extends DefaultSilSuite {
  private val siliconTestDirectories: Seq[String] = List(
    "experimental"
  )

  private val silTestDirectories: Seq[String] = List(
    "all"//,
//    "all/basic",
//    "all/chalice",
//    "all/sequences",
//    "all/issues/silicon"
  )

  override def testDirectories: Seq[String] = siliconTestDirectories ++ silTestDirectories

  override def frontend(verifier: Verifier, files: Seq[Path]): Frontend = {
    val fe = new SiliconFrontend()

    fe.init(verifier)
    fe.reset(files)
    fe
  }

  def verifiers = List(
    new Silicon(optionsFromScalaTestConfigMap(configMap),
                Seq(("startedBy", "semper.silicon.SiliconTests"))))

  private def optionsFromScalaTestConfigMap(configMap: Map[String, Any]): Seq[String] =
    configMap.flatMap{case (k, v) => Seq("--" + k, v.toString)}.toSeq
}

private class SiliconFrontend extends SilFrontend {
  def createVerifier(fullCmd: String) = sys.error("Implementation missing")
    /* 2013-05-03 Malte:
     *   It is not clear to me when this method would actually be called if it is used in a test
     *   suite that extends DefaultSilSuite. In Carbon/.../Carbon.scala the method returns a new
     *   instance of Carbon, but it doesn't seem to be used in the context of a test suite.
     *   Probably, because the test suite creates its own instance (see SiliconTests.frontend).
     */
}

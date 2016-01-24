package quasar.api.services

import quasar.Predef._
import quasar.Variables
import quasar.effect.KeyValueStore
import quasar.fp.free
import quasar.fp.prism._
import quasar.fs._
import quasar.fs.mount._
import quasar.recursionschemes.Fix
import quasar.sql._

import argonaut._, Argonaut._
import monocle.Lens
import org.http4s._
import org.http4s.argonaut._
import org.http4s.server._
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import pathy.Path._
import pathy.scalacheck._
import scalaz.{Lens => _, _}
import scalaz.concurrent.Task

class MetadataServiceSpec extends Specification with ScalaCheck with FileSystemFixture with Http4s {
  import InMemory._
  import metadata.FsNode

  type MetadataEff[A] = Coproduct[QueryFileF, MountingF, A]

  def runQuery(mem: InMemState): QueryFile ~> Task =
    new (QueryFile ~> Task) {
      def apply[A](fs: QueryFile[A]) =
        Task.now(queryFile(fs).eval(mem))
    }

  def runMount(mnts: Map[APath, MountConfig2]): Mounting ~> Task =
    new (Mounting ~> Task) {
      type F[A] = State[Map[APath, MountConfig2], A]
      val mntr = Mounter.pure[MountConfigsF]
      val kvf = KeyValueStore.toState[State](Lens.id[Map[APath, MountConfig2]])
      def apply[A](ma: Mounting[A]) =
        Task.now(mntr(ma).foldMap(Coyoneda.liftTF[MountConfigs, F](kvf)).eval(mnts))
    }

  def service(mem: InMemState, mnts: Map[APath, MountConfig2]): HttpService =
    metadata.service[MetadataEff](free.interpret2[QueryFileF, MountingF, Task](
      Coyoneda.liftTF(runQuery(mem)),
      Coyoneda.liftTF(runMount(mnts))))

  import posixCodec.printPath

  "Metadata Service" should {
    "respond with NotFound" >> {
      // TODO: escaped paths do not survive being embedded in error messages
      "if directory does not exist" ! prop { dir: AbsDirOf[AlphaCharacters] =>
        val path:String = printPath(dir.path)
        val response = service(InMemState.empty, Map())(Request(uri = Uri(path = path))).run
        response.status must_== Status.NotFound
        response.as[Json].run must_== Json("error" := s"${printPath(dir.path)} doesn't exist")
      }

      "file does not exist" ! prop { file: AbsFileOf[AlphaCharacters] =>
        val path:String = posixCodec.printPath(file.path)
        val response = service(InMemState.empty, Map())(Request(uri = Uri(path = path))).run
        response.status must_== Status.NotFound
        response.as[Json].run must_== Json("error" := s"File not found: $path")
      }

      "if file with same name as existing directory (without trailing slash)" ! prop { s: SingleFileMemState =>
        depth(s.file) > 1 ==> {
          val parent = fileParent(s.file)
          // .get here is because we know thanks to the property guard, that the parent directory has a name
          val fileWithSameName = parentDir(parent).get </> file(dirName(parent).get.value)
          val path = printPath(fileWithSameName)
          val response = service(s.state, Map())(Request(uri = Uri(path = path))).run
          response.status must_== Status.NotFound
          response.as[Json].run must_== Json("error" := s"File not found: $path")
        }
      }
    }

    "respond with OK" >> {
      "and empty list for existing empty directory" >>
        todo // The current in-memory filesystem does not support empty directories

      "respond with list of children for existing nonempty directory" ! prop { s: NonEmptyDir =>
        val childNodes = s.ls.map(p => FsNode(p.swap, None))

        service(s.state, Map())(Request(uri = Uri(path = printPath(s.dir))))
          .as[Json].run must_== Json("children" := childNodes.sorted)
      }

      "and mounts when any children happen to be mount points" ! prop {
        (fName: AlphaCharacters, dName: AlphaCharacters, mName: AlphaCharacters, vName: AlphaCharacters) => (fName != vName && dName != mName) ==> {
        val parent: ADir = rootDir </> dir("foo")
        val vcfg = MountConfig2.viewConfig(
          Fix(IntLiteralF[Expr](1)),
          Variables.empty)
        val fsCfg = MountConfig2.fileSystemConfig(
          FileSystemType("testfs"),
          ConnectionUri("fs:bar"))
        val mnts = Map[APath, MountConfig2](
          (parent </> file(vName.value), vcfg),
          (parent </> dir(mName.value), fsCfg))
        val mem = InMemState fromFiles Map(
          (parent </> file(fName.value), Vector()),
          (parent </> dir(dName.value) </> file("quux"), Vector()),
          (parent </> file(vName.value), Vector()),
          (parent </> dir(mName.value) </> file("bar"), Vector()))

        service(mem, mnts)(Request(uri = Uri(path = posixCodec.printPath(parent))))
          .as[Json].run must_== Json("children" := List(
            FsNode(fName.value, "file", None),
            FsNode(dName.value, "directory", None),
            FsNode(vName.value, "file", Some("view")),
            FsNode(mName.value, "directory", Some("testfs"))
          ).sorted)
      }}

      "and empty object for existing file" ! prop { s: SingleFileMemState =>
        service(s.state, Map())(Request(uri = Uri(path = s.path)))
          .as[Json].run must_== Json.obj()
      }
    }
  }
}

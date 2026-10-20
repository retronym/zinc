package sbt
package internal
package inc

import java.nio.file.Files
import xsbti.api._
import sbt.io.IO
import xsbt.api.{ SameAPI, ShowAPI }

/** What the bridge extracts for Merkle hashing (see [[Hashing]]). */
class MerkleExtractAPISpecification
    extends UnitSpec
    with CompilingSpecification
    with BridgeProviderTestkit {

  override def merkleHashing: Boolean = true

  /**
   * Checks if representation of the inherited Namer class (with a declared self variable)
   * is stable between compiling from source and unpickling.
   *
   * Members inherited from classes of this compilation's output are not materialised (Zinc
   * composes their hashes from the ancestor's own API), so `Global.Foo` must not depend on
   * whether `Namers` was compiled together with it or unpickled from the output directory.
   * Members inherited from a library are still materialised, from unpickled symbols, so there
   * the inherited `Namer` must have the same empty self type as when it is extracted from source.
   */
  "ExtractAPI under Merkle hashing" should
    "make a stable representation of a self variable that has no self type" in {
      val src1 =
        """|class Namers {
        |  class Namer { thisNamer => }
        |}
        |""".stripMargin
      val src2 =
        """|class Global {
        |  class Foo extends Namers
        |}
        |""".stripMargin
      def select(apis: Set[ClassLike], name: String): ClassLike = apis.find(_.name == name).get
      def memberNames(c: ClassLike): Set[String] =
        (c.structure.declared ++ c.structure.inherited).map(_.name).toSet

      val src1Api :: src2Api1 :: src2Api2 :: Nil =
        extractApisFromSrcs(List(src1, src2), List(src2)).toList: @unchecked
      val foo1 = select(src2Api1, "Global.Foo")
      val foo2 = select(src2Api2, "Global.Foo")
      assert(!memberNames(foo1).contains("Global.Foo.Namer"))
      assert(SameAPI(foo1, foo2))
      assert(src2Api1.map(_.name) === Set("Global", "Global.Foo"))
      assert(select(src1Api, "Namers.Namer").selfType.isInstanceOf[EmptyType])

      IO.withTemporaryDirectory { tempDir =>
        val mainDir = tempDir.toPath
        val libDir = mainDir.resolve("lib")
        Files.createDirectories(libDir)
        compileSrcs(libDir, src1)
        val (Seq(src2File), callback) =
          compileSrcs(mainDir, extraClasspath = List(libDir.resolve("target")))(
            List(List(src2))
          ): @unchecked
        val libApis = callback.apis(src2File)
        val namer = select(libApis, "Global.Foo.Namer")
        assert(namer.selfType.isInstanceOf[EmptyType])
        assert(memberNames(select(libApis, "Global.Foo")).contains("Global.Foo.Namer"))
      }
    }

  /**
   * An inherited class is seen through the parent type it is inherited from: `B`'s `AA` has
   * `def t: Int`, unlike `A.AA`. Zinc no longer extracts it as a class of its own (`B.AA`):
   * `AA` is declared in `A`, which Zinc analyses, so it hashes `B`'s `AA` by composing `A`'s
   * hash for `AA` with `B`'s linearized parents as seen from `B`. So `B`'s API must record
   * the instantiated parent, and change when it does.
   */
  it should "make a different representation for an inherited class" in {
    def src(targ: String) =
      s"""|class A[T] {
          |  abstract class AA { def t: T }
          |}
          |class B extends A[$targ]
      """.stripMargin
    val apis = extractApisFromSrc(src("Int")).map(a => a.name -> a).toMap
    assert(apis.keySet === Set("A", "A.AA", "B"))
    val apis2 = extractApisFromSrc(src("String")).map(a => a.name -> a).toMap
    assert(SameAPI(apis("A"), apis2("A")))
    assert(SameAPI(apis("A.AA"), apis2("A.AA")))
    assert(!SameAPI(apis("B"), apis2("B")))
  }

  /**
   * `Object.wait(long)` is read from a classfile, and scalac attaches its `@throws` annotation
   * only when its type is completed. A class inheriting it must get the same API whether or not
   * another unit in the run forced that type first, or its hashes change from one run to the next.
   */
  it should "extract inherited Java members the same whether or not their types were forced" in {
    val srcA = "class A { def f(): Unit = wait(1L) }"
    val srcB = "class B"
    val alone = extractApisFromSrcs(List(srcB)).head.head
    val afterA = extractApisFromSrcs(List(srcA, srcB)).last.head
    def waits(c: ClassLike) =
      c.structure.inherited.filter(_.name == "wait").map(ShowAPI.showDefinition(_)(using
        1
      )).sorted.toList
    assert(waits(alone).nonEmpty)
    assert(waits(alone) === waits(afterA))
    assert(SameAPI(alone, afterA))
  }
}

package is.hail.io

import is.hail.SparkSuite
import is.hail.check.Prop._
import is.hail.check.{Gen, Properties}
import is.hail.utils._
import is.hail.testUtils._
import is.hail.TestUtils
import is.hail.annotations.Annotation
import is.hail.variant._
import org.testng.annotations.Test

class SplitSuite extends SparkSuite {

  object Spec extends Properties("MultiSplit") {
    val splittableLocusAllelesGen = for {
      contig <- Gen.const("1")
      start <- Gen.choose(1, 100)
      motif <- Gen.oneOf("AT", "AC", "CT", "GA", "GT", "CCA", "CAT", "CCT")
      ref <- Gen.choose(1, 10).map(motif * _)
      alts <- Gen.distinctBuildableOf[Array](Gen.choose(1, 10).map(motif * _).filter(_ != ref))
    } yield Annotation(Locus(contig, start), (ref +: alts).toFastIndexedSeq)

    property("splitMulti maintains variants") = forAll(MatrixTable.gen(hc,
      VSMSubgen.random.copy(vGen = _ => splittableLocusAllelesGen))) { vds =>
      val method1 = TestUtils.splitMultiHTS(vds).variants.collect().toSet
      val method2 = vds.variants.flatMap { v =>
        v.altAlleles.iterator
          .map { aa =>
            Variant(v.contig, v.start, v.ref, Array(aa)).minRep
          }
      }.collect().toSet

      method1 == method2
    }
  }

  @Test def splitTest() {
    Spec.check()

    val vds = hc.importVCF("src/test/resources/split_test.vcf")
    val vds1 = TestUtils.splitMultiHTS(vds)

    val vds2 = hc.importVCF("src/test/resources/split_test_b.vcf")

    // test splitting and downcoding
    vds1.mapWithKeys((v, s, g) => ((v, s), g))
      .join(vds2.mapWithKeys((v, s, g) => ((v, s), g)))
      .foreach { case (k, (g1, g2)) =>
        if (g1 != g2)
          println(s"$g1, $g2")
        simpleAssert(g1 == g2)
      }

    assert(vds1.rowsTable().forall("row.wasSplit == (row.locus.position != 1180)"))
  }
}

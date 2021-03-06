package is.hail.io

import is.hail.SparkSuite
import is.hail.io.plink.ExportPlink
import is.hail.utils._
import is.hail.TestUtils
import org.testng.annotations.Test

import scala.io.Source
import scala.language._
import scala.sys.process._

class ExportPlinkSuite extends SparkSuite {

  def rewriteBimIDs(file: String) {
    val parsed = hadoopConf.readFile(file) { is =>
      Source.fromInputStream(is)
        .getLines()
        .toList
        .map(line => line
          .split("\t"))
        .map(arr =>
          s"${ arr(0) }\t${ s"${ arr(0) }:${ arr(3) }:${ arr(4) }:${ arr(5) }" }\t${ arr(2) }\t${ arr(3) }\t${ arr(4) }\t${ arr(5) }\n")
    }
    hadoopConf.writeTable(file, parsed)
  }

  @Test def testBiallelic() {

    val hailFile = tmpDir.createTempFile("hail")

    val vds = TestUtils.splitMultiHTS(hc.importVCF("src/test/resources/sample.vcf"))
    ExportPlink(vds, hailFile)

    rewriteBimIDs(hailFile + ".bim")

    val localBFile = tmpDir.createLocalTempFile("plink")

    // use plink to convert sample.vcf to a bed/bim/fam file
    s"plink --vcf src/test/resources/sample.vcf --make-bed --out ${ uriPath(localBFile) } --const-fid --keep-allele-order" !

    rewriteBimIDs(localBFile + ".bim")

    val localMergeFile = tmpDir.createLocalTempFile("merge")

    val localHailFile = tmpDir.createLocalTempFile("hail")
    hadoopConf.copy(hailFile + ".bed", localHailFile + ".bed")
    hadoopConf.copy(hailFile + ".bim", localHailFile + ".bim")
    hadoopConf.copy(hailFile + ".fam", localHailFile + ".fam")

    // use plink to assert that the concordance rate is 1
    val exitCode = s"plink --bfile ${ uriPath(localBFile) } --bmerge ${ uriPath(localHailFile) } --merge-mode 6 --out ${ uriPath(localMergeFile) }" !

    // assert that plink exited successfully
    assert(exitCode == 0)

    // assert that the .diff file is empty of non-header columns
    assert(
      hadoopConf.readFile(localMergeFile + ".diff") { is =>
        Source.fromInputStream(is)
          .getLines()
          .toIndexedSeq
          .map(_.split(" +").filter(!_.isEmpty).toIndexedSeq) == IndexedSeq(IndexedSeq("SNP", "FID", "IID", "NEW", "OLD"))
      }
    )
  }
}

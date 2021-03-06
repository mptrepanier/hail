package is.hail.rvd

import is.hail.annotations.{KeyedRow, RegionValue, UnsafeRow}
import is.hail.expr.types.TStruct
import is.hail.sparkextras._
import is.hail.io.CodecSpec
import is.hail.utils._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

object UnpartitionedRVD {
  def empty(sc: SparkContext, rowType: TStruct): UnpartitionedRVD =
    new UnpartitionedRVD(rowType, ContextRDD.empty[RVDContext, RegionValue](sc))
}

class UnpartitionedRVD(val rowType: TStruct, val crdd: ContextRDD[RVDContext, RegionValue]) extends RVD {
  self =>

  def this(rowType: TStruct, rdd: RDD[RegionValue]) =
    this(rowType, ContextRDD.weaken[RVDContext](rdd))

  val rdd = crdd.run

  def filter(f: (RegionValue) => Boolean): UnpartitionedRVD = new UnpartitionedRVD(rowType, crdd.filter(f))

  def persist(level: StorageLevel): UnpartitionedRVD = {
    val PersistedRVRDD(persistedRDD, iterationRDD) = persistRVRDD(level)
    new UnpartitionedRVD(rowType, iterationRDD) {
      override def storageLevel: StorageLevel = persistedRDD.getStorageLevel

      override def persist(newLevel: StorageLevel): UnpartitionedRVD = {
        if (newLevel == StorageLevel.NONE)
          unpersist()
        else {
          persistedRDD.persist(newLevel)
          this
        }
      }

      override def unpersist(): UnpartitionedRVD = {
        persistedRDD.unpersist()
        self
      }
    }
  }

  def sample(withReplacement: Boolean, p: Double, seed: Long): UnpartitionedRVD =
    new UnpartitionedRVD(rowType, crdd.sample(withReplacement, p, seed))

  override protected def rvdSpec(codecSpec: CodecSpec, partFiles: Array[String]): RVDSpec =
    UnpartitionedRVDSpec(rowType, codecSpec, partFiles)

  def coalesce(maxPartitions: Int, shuffle: Boolean): UnpartitionedRVD =
    new UnpartitionedRVD(rowType, crdd.coalesce(maxPartitions, shuffle = shuffle))

  def constrainToOrderedPartitioner(
    ordType: OrderedRVDType,
    newPartitioner: OrderedRVDPartitioner
  ): OrderedRVD = {

    assert(ordType.rowType == rowType)

    val localRowType = rowType
    val pkOrdering = ordType.pkType.ordering
    val rangeTree = newPartitioner.rangeTree
    val filtered = crdd.mapPartitions { it =>
      val ur = new UnsafeRow(localRowType, null, 0)
      val key = new KeyedRow(ur, ordType.pkRowFieldIdx)
      it.filter { rv =>
        ur.set(rv)
        rangeTree.contains(pkOrdering, key)
      }
    }

    OrderedRVD.shuffle(ordType, newPartitioner, filtered)
  }
}

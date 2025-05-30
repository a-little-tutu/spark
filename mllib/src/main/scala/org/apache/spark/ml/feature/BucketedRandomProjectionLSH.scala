/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.feature

import java.io.{DataInputStream, DataOutputStream}

import scala.util.Random

import org.apache.hadoop.fs.Path

import org.apache.spark.annotation.Since
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared.HasSeed
import org.apache.spark.ml.util._
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.ArrayImplicits._

/**
 * Params for [[BucketedRandomProjectionLSH]].
 */
private[ml] trait BucketedRandomProjectionLSHParams extends Params {

  /**
   * The length of each hash bucket, a larger bucket lowers the false negative rate. The number of
   * buckets will be `(max L2 norm of input vectors) / bucketLength`.
   *
   *
   * If input vectors are normalized, 1-10 times of pow(numRecords, -1/inputDim) would be a
   * reasonable value
   * @group param
   */
  val bucketLength: DoubleParam = new DoubleParam(this, "bucketLength",
    "the length of each hash bucket, a larger bucket lowers the false negative rate.",
    ParamValidators.gt(0))

  /** @group getParam */
  final def getBucketLength: Double = $(bucketLength)
}

/**
 * Model produced by [[BucketedRandomProjectionLSH]], where multiple random vectors are stored. The
 * vectors are normalized to be unit vectors and each vector is used in a hash function:
 *    `h_i(x) = floor(r_i.dot(x) / bucketLength)`
 * where `r_i` is the i-th random unit vector. The number of buckets will be `(max L2 norm of input
 * vectors) / bucketLength`.
 *
 * @param randMatrix A matrix with each row representing a hash function.
 */
@Since("2.1.0")
class BucketedRandomProjectionLSHModel private[ml](
    override val uid: String,
    private[ml] val randMatrix: Matrix)
  extends LSHModel[BucketedRandomProjectionLSHModel] with BucketedRandomProjectionLSHParams {

  // For ml connect only
  private[ml] def this() = this("", Matrices.empty)

  private[ml] def this(uid: String, randUnitVectors: Array[Vector]) = {
    this(uid, Matrices.fromVectors(randUnitVectors.toImmutableArraySeq))
  }

  private[ml] def randUnitVectors: Array[Vector] = randMatrix.rowIter.toArray

  /** @group setParam */
  @Since("2.4.0")
  override def setInputCol(value: String): this.type = super.set(inputCol, value)

  /** @group setParam */
  @Since("2.4.0")
  override def setOutputCol(value: String): this.type = super.set(outputCol, value)

  @Since("2.1.0")
  override protected[ml] def hashFunction(elems: Vector): Array[Vector] = {
    val hashVec = new DenseVector(Array.ofDim[Double](randMatrix.numRows))
    BLAS.gemv(1.0 / $(bucketLength), randMatrix, elems, 0.0, hashVec)
    // TODO: Output vectors of dimension numHashFunctions in SPARK-18450
    hashVec.values.map(h => Vectors.dense(h.floor))
  }

  @Since("2.1.0")
  override protected[ml] def keyDistance(x: Vector, y: Vector): Double = {
    Math.sqrt(Vectors.sqdist(x, y))
  }

  @Since("2.1.0")
  override protected[ml] def hashDistance(x: Array[Vector], y: Array[Vector]): Double = {
    // Since it's generated by hashing, it will be a pair of dense vectors.
    var distance = Double.MaxValue
    var i = 0
    while (i < x.length) {
      val vx = x(i).toArray
      val vy = y(i).toArray
      var j = 0
      var d = 0.0
      while (j < vx.length && d < distance) {
        val diff = vx(j) - vy(j)
        d += diff * diff
        j += 1
      }
      if (d == 0) return 0.0
      if (d < distance) distance = d
      i += 1
    }
    distance
  }

  @Since("2.1.0")
  override def copy(extra: ParamMap): BucketedRandomProjectionLSHModel = {
    val copied = new BucketedRandomProjectionLSHModel(uid, randMatrix).setParent(parent)
    copyValues(copied, extra)
  }

  @Since("2.1.0")
  override def write: MLWriter = {
    new BucketedRandomProjectionLSHModel.BucketedRandomProjectionLSHModelWriter(this)
  }

  @Since("3.0.0")
  override def toString: String = {
    s"BucketedRandomProjectionLSHModel: uid=$uid, numHashTables=${$(numHashTables)}"
  }
}

/**
 * This [[BucketedRandomProjectionLSH]] implements Locality Sensitive Hashing functions for
 * Euclidean distance metrics.
 *
 * The input is dense or sparse vectors, each of which represents a point in the Euclidean
 * distance space. The output will be vectors of configurable dimension. Hash values in the
 * same dimension are calculated by the same hash function.
 *
 * References:
 *
 * 1. <a href="https://en.wikipedia.org/wiki/Locality-sensitive_hashing#Stable_distributions">
 * Wikipedia on Stable Distributions</a>
 *
 * 2. Wang, Jingdong et al. "Hashing for similarity search: A survey." arXiv preprint
 * arXiv:1408.2927 (2014).
 */
@Since("2.1.0")
class BucketedRandomProjectionLSH(override val uid: String)
  extends LSH[BucketedRandomProjectionLSHModel]
    with BucketedRandomProjectionLSHParams with HasSeed {

  @Since("2.1.0")
  override def setInputCol(value: String): this.type = super.setInputCol(value)

  @Since("2.1.0")
  override def setOutputCol(value: String): this.type = super.setOutputCol(value)

  @Since("2.1.0")
  override def setNumHashTables(value: Int): this.type = super.setNumHashTables(value)

  @Since("2.1.0")
  def this() = this(Identifiable.randomUID("brp-lsh"))

  /** @group setParam */
  @Since("2.1.0")
  def setBucketLength(value: Double): this.type = set(bucketLength, value)

  /** @group setParam */
  @Since("2.1.0")
  def setSeed(value: Long): this.type = set(seed, value)

  @Since("2.1.0")
  override protected[this] def createRawLSHModel(
    inputDim: Int): BucketedRandomProjectionLSHModel = {
    val rng = new Random($(seed))
    val localNumHashTables = $(numHashTables)
    val values = Array.fill(localNumHashTables * inputDim)(rng.nextGaussian())
    var i = 0
    while (i < localNumHashTables) {
      val offset = i * inputDim
      val norm = BLAS.javaBLAS.dnrm2(inputDim, values, offset, 1)
      if (norm != 0) BLAS.javaBLAS.dscal(inputDim, 1.0 / norm, values, offset, 1)
      i += 1
    }
    val randMatrix = new DenseMatrix(localNumHashTables, inputDim, values, true)
    new BucketedRandomProjectionLSHModel(uid, randMatrix)
  }

  @Since("2.1.0")
  override def transformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(inputCol), new VectorUDT)
    validateAndTransformSchema(schema)
  }

  @Since("2.1.0")
  override def copy(extra: ParamMap): this.type = defaultCopy(extra)
}

@Since("2.1.0")
object BucketedRandomProjectionLSH extends DefaultParamsReadable[BucketedRandomProjectionLSH] {

  @Since("2.1.0")
  override def load(path: String): BucketedRandomProjectionLSH = super.load(path)
}

@Since("2.1.0")
object BucketedRandomProjectionLSHModel extends MLReadable[BucketedRandomProjectionLSHModel] {
  // TODO: Save using the existing format of Array[Vector] once SPARK-12878 is resolved.
  private[ml] case class Data(randUnitVectors: Matrix)

  private[ml] def serializeData(data: Data, dos: DataOutputStream): Unit = {
    import ReadWriteUtils._
    serializeMatrix(data.randUnitVectors, dos)
  }

  private[ml] def deserializeData(dis: DataInputStream): Data = {
    import ReadWriteUtils._
    val randUnitVectors = deserializeMatrix(dis)
    Data(randUnitVectors)
  }

  @Since("2.1.0")
  override def read: MLReader[BucketedRandomProjectionLSHModel] = {
    new BucketedRandomProjectionLSHModelReader
  }

  @Since("2.1.0")
  override def load(path: String): BucketedRandomProjectionLSHModel = super.load(path)

  private[BucketedRandomProjectionLSHModel] class BucketedRandomProjectionLSHModelWriter(
    instance: BucketedRandomProjectionLSHModel) extends MLWriter {

    override protected def saveImpl(path: String): Unit = {
      DefaultParamsWriter.saveMetadata(instance, path, sparkSession)
      val data = Data(instance.randMatrix)
      val dataPath = new Path(path, "data").toString
      ReadWriteUtils.saveObject[Data](dataPath, data, sparkSession, serializeData)
    }
  }

  private class BucketedRandomProjectionLSHModelReader
    extends MLReader[BucketedRandomProjectionLSHModel] {

    /** Checked against metadata when loading model */
    private val className = classOf[BucketedRandomProjectionLSHModel].getName

    override def load(path: String): BucketedRandomProjectionLSHModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sparkSession, className)

      val dataPath = new Path(path, "data").toString
      val data = ReadWriteUtils.loadObject[Data](dataPath, sparkSession, deserializeData)
      val model = new BucketedRandomProjectionLSHModel(metadata.uid, data.randUnitVectors)

      metadata.getAndSetParams(model)
      model
    }
  }
}

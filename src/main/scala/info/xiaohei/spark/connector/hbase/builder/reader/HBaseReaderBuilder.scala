package info.xiaohei.spark.connector.hbase.builder.reader

import info.xiaohei.spark.connector.hbase.salt.SaltProducerFactory
import info.xiaohei.spark.connector.hbase.transformer.reader.DataReader
import info.xiaohei.spark.connector.hbase.{HBaseCommonUtils, HBaseConf}
import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark.SparkContext
import org.apache.spark.rdd.{HBaseScanRDD, NewHadoopRDD, RDD}

import scala.reflect.ClassTag

/**
  * Author: xiaohei
  * Date: 2017/3/21
  * Email: xiaohei.info@gmail.com
  * Host: www.xiaohei.info
  */
case class HBaseReaderBuilder[R: ClassTag] private[hbase](
                                                           @transient sc: SparkContext,
                                                           private[hbase] val tableName: String,
                                                           private[hbase] val defaultColumnFamily: Option[String] = None,
                                                           private[hbase] val columns: Iterable[String] = Seq.empty,
                                                           private[hbase] val startRow: Option[String] = None,
                                                           private[hbase] val stopRow: Option[String] = None,
                                                           private[hbase] val salts: Iterable[String] = Seq.empty
                                                         ) {
  def select(columns: String*): HBaseReaderBuilder[R] = {
    require(this.columns.isEmpty, "Columns have already been set")
    require(columns.nonEmpty, "You should provide at least one column")
    this.copy(columns = columns)
  }

  def select(columns: Iterable[String]): HBaseReaderBuilder[R] = {
    require(this.columns.isEmpty, "Columns have already been set")
    require(columns.nonEmpty, "You should provide at least one column")
    this.copy(columns = columns)
  }

  def inColumnFamily(columnFamily: String): HBaseReaderBuilder[R] = {
    require(this.defaultColumnFamily.isEmpty, "Default column family has already been set")
    require(columnFamily.nonEmpty, "Invalid column family provided")
    this.copy(defaultColumnFamily = Some(columnFamily))
  }

  def withStartRow(startRow: String): HBaseReaderBuilder[R] = {
    require(startRow.nonEmpty, s"Invalid start row '$startRow'")
    require(this.startRow.isEmpty, "Start row has already been set")
    this.copy(startRow = Some(startRow))
  }

  def withEndRow(endRow: String): HBaseReaderBuilder[R] = {
    require(endRow.nonEmpty, s"Invalid stop row '$endRow'")
    require(this.stopRow.isEmpty, "Stop row has already been set")
    this.copy(stopRow = Some(endRow))
  }

  def withSalt(salts: Iterable[String]) = {
    require(salts.size > 1, "Invalid salting. Two or more elements are required")
    require(this.salts.isEmpty, "Salting has already been set")

    this.copy(salts = salts)
  }

  private[hbase] def withRanges(startRow: Option[String], stopRow: Option[String]) = {
    copy(startRow = startRow, stopRow = stopRow)
  }
}

trait HBaseReaderBuilderConversions extends Serializable {
  implicit def toHBaseRDD[R: ClassTag](builder: HBaseReaderBuilder[R])
                                      (implicit reader: DataReader[R], saltProducerFactory: SaltProducerFactory[String]): RDD[R] = {
    if (builder.salts.isEmpty) {
      toSimpleHBaseRdd(builder)
    } else {
      val saltLength = saltProducerFactory.getHashProducer(builder.salts).singleSaltength
      val sortedSalts = builder.salts.toList.sorted.map(Some(_))
      val ranges = sortedSalts.zip(sortedSalts.drop(1) :+ None)
      val rddSeq = ranges.map {
        salt =>
          builder.withRanges(
            if (builder.startRow.nonEmpty) Some(salt._1.get + builder.startRow.get) else salt._1,
            if (builder.stopRow.nonEmpty) Some(salt._1.get + builder.stopRow.get) else salt._2
          )
      }.map {
        builder =>
          toSimpleHBaseRdd(builder, saltLength).asInstanceOf[RDD[R]]
      }
      val sc = rddSeq.head.sparkContext
      new HBaseSaltRDD[R](sc, rddSeq)
    }
  }

  private def toSimpleHBaseRdd[R: ClassTag](builder: HBaseReaderBuilder[R], saltsLength: Int = 0)
                                           (implicit reader: DataReader[R]): HBaseSimpleRDD[R] = {
    val hbaseConfig = HBaseConf.createFromSpark(builder.sc.getConf).createHadoopBaseConf()
    hbaseConfig.set(TableInputFormat.INPUT_TABLE, builder.tableName)
    require(builder.columns.nonEmpty, "No columns have been defined for the operation")
    val columnNames = builder.columns
    val fullColumnNames = HBaseCommonUtils.getFullColumnNames(builder.defaultColumnFamily, columnNames)
    if (fullColumnNames.nonEmpty) {
      hbaseConfig.set(TableInputFormat.SCAN_COLUMNS, fullColumnNames.mkString(" "))
    }
    if (builder.startRow.nonEmpty) {
      hbaseConfig.set(TableInputFormat.SCAN_ROW_START, builder.startRow.get)
    }
    if (builder.stopRow.nonEmpty) {
      hbaseConfig.set(TableInputFormat.SCAN_ROW_STOP, builder.stopRow.get)
    }

    //krb认证
    val rdd = if (hbaseConfig.get("spark.hbase.krb.principal") == null || hbaseConfig.get("spark.hbase.krb.keytab") == null) {
      //todo:asInstanceOf
      builder.sc.newAPIHadoopRDD(hbaseConfig
        , classOf[TableInputFormat]
        , classOf[ImmutableBytesWritable]
        , classOf[Result])
        .asInstanceOf[NewHadoopRDD[ImmutableBytesWritable, Result]]
    } else {
      new HBaseScanRDD[ImmutableBytesWritable, Result](builder.sc
        , classOf[TableInputFormat]
        , classOf[ImmutableBytesWritable]
        , classOf[Result]
        , hbaseConfig)
    }
    new HBaseSimpleRDD[R](rdd, builder, saltsLength)
  }
}

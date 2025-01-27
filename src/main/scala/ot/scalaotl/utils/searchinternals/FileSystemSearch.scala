package ot.scalaotl
package utils
package searchinternals

import org.apache.commons.lang.StringEscapeUtils.unescapeJava
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.types.{LongType, StringType, StructField, StructType}
import org.apache.spark.sql.{AnalysisException, DataFrame, Row, SparkSession, functions => F}
import ot.AppConfig.{config, getLogLevel}
import ot.dispatcher.sdk.core.CustomException.E00004
import ot.scalaotl.config.OTLIndexes
import ot.scalaotl.extensions.DataFrameExt._
import ot.scalaotl.extensions.StringExt._

import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

class FileSystemSearch(spark: SparkSession, searchId: Int, fieldsUsedInFullQuery: Seq[String],
                       fs: FileSystem, indexPath: String, indexName: String, query: String, tws: Long, twf: Long,
                       preview: Boolean, fullReadFlag: Boolean = false, isCache: Boolean = false) extends OTLIndexes {
  val log: Logger = Logger.getLogger(this.getClass.getName)
  log.setLevel(Level.toLevel(getLogLevel(config, this.getClass.getSimpleName)))

  val defaultFields: List[String] = List("_time", "_raw")
  val externalSchema: Boolean = otlconfig.getString("schema.external_schema").toBoolean
  val mergeSchema: Boolean = otlconfig.getString("schema.merge_schema").toBoolean

  private def getColumns(_df: DataFrame): List[String] = {
    def matchCols(r: Regex): Array[String] = _df.columns.filter(r.pattern.matcher(_).matches)

    var i = 0
    val res = for(field <- fieldsUsedInFullQuery if i < max_cols)
      yield
      // select all fields matched with wildcards; Ex.: t* -> time, temp, t1.value
      if (field.contains("*")) {
        val r = field.escapeChars("""<([{\^-=$!|]})?+.>""").replace("*", ".*").r
        val cols = matchCols(r).sorted.take(max_cols).toList //Excuding nested fields from search
        i += cols.size
        cols
        // if field contains multi-value 'token' {}, select all fields which are elements of this multi-value
      } else if (field.contains("{}")) {
        i += 1
        matchCols(field.replace("{}", "\\[\\d+\\]").r).sorted.take(max_mv_size).toList
        // if field contains {\d}, just replace curly brackets with square ones
      } else if ("""^.*\{\d+}.*$""".r.pattern.matcher(field).matches) {
        i += 1
        List(field.replaceByMap(Map("{" -> "[", "}" -> "]")))
      }
      else {
        i += 1
        List(field)
      }
    res.toList.flatten
  }

  private def checkSchema(df: DataFrame): DataFrame = {
    var fdf = spark.emptyDataFrame
    val cols = fieldsUsedInFullQuery.map(_.stripBackticks())
    log.debug(s"[SearchId:$searchId] fieldsUsedInFullQuery = $cols")
    val schema = df.schema.names.toList
    log.debug(s"[SearchId:$searchId] schema = $schema")
    log.debug(s"[SearchId:$searchId] fullReadFlag = $fullReadFlag")
    if (fullReadFlag) {
      fdf = df
    } else {
      if (cols.isEmpty) {
        val limitedFilterCols = defaultFields.map(_.addSurroundedBackticks)
        log.debug(s"[SearchId:$searchId] FilterIntersectionCols = $limitedFilterCols")
        fdf = df.select(limitedFilterCols.head, limitedFilterCols.tail: _*)
      } else {
        val colsFromSearch = getColumns(df).map(_.stripBackticks())
        log.debug(s"[SearchId:$searchId] colsFromSearch=$colsFromSearch")
        val colsWithDefault = defaultFields ++ colsFromSearch
        log.debug(s"[SearchId:$searchId] colsFromSearchWithDefault = $colsWithDefault")
        val notExistedColumns = colsWithDefault.distinct.diff(schema)
        log.debug(s"[SearchId:$searchId] NotExistedColumns = $notExistedColumns")
        val colsToBeSelected = colsWithDefault.distinct.map(_.addSurroundedBackticks)
        log.debug(s"[SearchId:$searchId] ColsToBeSelected = $colsToBeSelected")
        fdf = notExistedColumns.foldLeft(df) { (acc, col) => acc.withColumn(col, lit(null)) }
        fdf = fdf.select(colsToBeSelected.head, colsToBeSelected.tail: _*)
        fdf = fdf.columns.foldLeft(fdf) { (memoDF, colName) => memoDF.withColumnRenamed(colName, unescapeJava(colName)) }
      }
    }
    fdf
  }

  private def readParquetParallel(files: ListBuffer[String]): DataFrame = {
    log.debug(s"[SearchId:$searchId] Fields in query: $fieldsUsedInFullQuery")
    val df = if(externalSchema) {
      val ddlSchema = getSchemaBySpark(files)
      spark.read.schema(ddlSchema).parquet(files.seq: _*)
    }else if(mergeSchema)
      spark.read.option("mergeSchema", "true").parquet(files.seq: _*)
    else
      spark.read.parquet(files.seq: _*)
    log.debug(s"[SearchId:$searchId] Parquet files readed")
    log.debug(s"[SearchId:$searchId] Start checking schema")
    val fdf = checkSchema(df.withColumn("index", lit(indexName)))
    val fdd = searchInDataFrame(fdf)
    fdd
  }

  private def readParquetSequential(files: ListBuffer[String]): DataFrame = {
    log.debug(s"[SearchId:$searchId] Fields in query: $fieldsUsedInFullQuery")
    var fdf = spark.emptyDataFrame
    //val df = spark.sqlContext.read.option("mergeSchema", "true").parquet(files.seq: _*)//.select("_time","_raw")
    import util.control.Breaks._
    breakable {
      for (file <- files) {
        log.debug(s"Bucket: $file")
        val df = spark.sqlContext.read.parquet(file)
        val fd = checkSchema(df)
        val fdd = searchInDataFrame(fd)
        fdf = fdf.append(fdd)
        if (fdf.count() > 100000) {
            log.debug(s"[SearchId:$searchId] search stopped")
            break
        }
      }
    }
    fdf
  }

  private def searchInDataFrame(df: DataFrame): DataFrame = {
    var fdf = df.withColumn("_time", F.col("_time").cast(LongType))
      // this is really necessary since filtering by timerange selects blocks of data the size of a bucket period
      .filter(s"_time >= $tws AND _time < $twf")
    log.debug(s"[SearchId:$searchId] searchInDataFrame time filter: from $tws to $twf")
    log.debug(s"[SearchId:$searchId] searchInDataFrame query: $query")
    try{
      if (query.nonEmpty) {
        val newFilter = fixFilter(F.expr(query).expr)
        if (fdf.schema.length > 2) {
          fdf = fdf.filter(F.expr(newFilter.sql))
        }
      }
    }
    catch {
      case ex: AnalysisException => throw ex
    }
    fdf
  }


  /** Gets index buckets, filters them, returns df with data required to filters.
   * Step 1. Creates empty DataFrame because list of accepted buckets may be empty.
   * Step 2. Checks if index presents.
   * Step 3. Gets list of buckets filtered by TimeRange.
   * Step 4. Returns empty DataFrame if list is empty.
   * Step 5. Gets list of buckets filtered by  BloomFilter.
   * Step 6. Returns empty DataFrame if list is empty.
   * Step 7. Read the parquets in series or in parallel depending on the preview parameter
   */
  def search(): Try[DataFrame] = {
    log.debug(s"$searchId FileSystem: $fs, indexPath: $indexPath, index: $indexName")
    // Step 1. Creates empty DataFrame because list of accepted buckets may be empty.
    var fdf = spark.createDataFrame(spark.sparkContext.emptyRDD[Row], StructType(Seq(StructField("_raw", StringType), StructField("_time", LongType))))
    // Step 2. Checks if index presents.
    if (!fs.exists(new Path(indexPath + indexName))){
      log.debug(s"Index in $fs: $indexName not found")
      return Failure(E00004(searchId, indexName))}
    // Step 3. Gets list of buckets filtered by time range.
    val bucketsTimeRange = Timerange.getBucketsByTimerange(fs, indexPath, indexName, tws, twf, isCache)
    log.debug(s"[SearchId:$searchId] Buckets by timerange $bucketsTimeRange")
    log.info(s"[SearchId:$searchId] ${bucketsTimeRange.length} Buckets by timerange")
    // Step 4. Returns empty DataFrame if list is empty.
    if (bucketsTimeRange.isEmpty) return Success(fdf)

    var bucketsBloomFilter = ListBuffer[String]()
    if (query.nonEmpty){
      bucketsBloomFilter = FilterBloom.getBucketsByFB(fs, indexPath, indexName, bloomFileName, bucketsTimeRange, query)
      log.debug(s"[SearchId:$searchId] Buckets by BloomFilter $bucketsBloomFilter")
      log.info(s"[SearchId:$searchId] ${bucketsBloomFilter.length} Buckets by BloomFilter")
    }
    else {
      bucketsBloomFilter = bucketsTimeRange
      log.debug(s"[SearchId:$searchId] Query is empty. No BloomFilter used ")
    }
    if (bucketsBloomFilter.isEmpty) return Success(fdf)

    val files = bucketsBloomFilter.map(x => s"""file:$indexPath$indexName/$x/""")
    log.debug(s"[SearchId:$searchId] FilesPath $files")

    if (preview) {
      log.debug(s"[SearchId:$searchId] Enable Preview Mode")
      fdf = readParquetSequential(files)}
    else {
      log.debug(s"[SearchId:$searchId] Enable Parallel Mode")
      fdf = readParquetParallel(files)
    }
    Success(fdf)
  }

  // TODO. Rewrite as soon as possible
  // If field name in query contains dots (.), catalyst parses it into list of fields splitted by dot.
  // In this case make mkString(".")
  private def fixFilter(ex: Expression): Expression = {
    val newEx = ex match {
      case UnresolvedAttribute(list) =>
        val newList = if (list.size > 1) List(list.mkString(".")) else list
        UnresolvedAttribute(newList)
      case _ => ex
    }
    newEx.mapChildren(fixFilter)
  }

  private def getSchemaBySpark(bucketsPaths: ListBuffer[String]): StructType = {
    import org.apache.spark.sql.functions._
    val files = bucketsPaths.map(_ + "*.schema")
    val df = spark.sqlContext.read.option("header", "false").csv(files :_* )
    val res = df.agg(concat_ws(", ", collect_set("_c0")).as("res")).first.getString(0)
    org.apache.spark.sql.types.StructType.fromDDL(res)
  }
}

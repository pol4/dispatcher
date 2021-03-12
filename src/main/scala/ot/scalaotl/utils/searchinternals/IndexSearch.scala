package ot.scalaotl
package utils
package searchinternals

import ot.scalaotl.config.OTLIndexes
import org.apache.log4j.Logger
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession, functions => F}
import org.apache.spark.sql.types.LongType
import scala.util.{Try, Success, Failure}
import org.apache.spark.sql.AnalysisException
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import ot.scalaotl.extensions.DataFrameExt._

class IndexSearch(spark: SparkSession, log: Logger, item: (String, Map[String, String]), searchId: Int, fieldsUsedInFullQuery: Seq[String], preview: Boolean, fullReadFlag: Boolean = false) extends OTLIndexes
{   

  val index: String = item._1
  val sql: String = item._2("query")
  val _tws: Int = item._2("tws").toInt
  val _twf: Int = item._2("twf").toInt
  val tws = _tws
  val twf = _twf match {
    case 0 => Long.MaxValue
    case _ => _twf
  }


  def getMergedDf(df_disk: DataFrame, df_cache: DataFrame): DataFrame =
  {
    val jdf = df_cache.agg(F.min("_time")).na.fill(Long.MaxValue, Seq("min(_time)"))
    val df_disk_filter = df_disk.crossJoin(jdf).filter(F.col("_time") < F.col("min(_time)")).drop("min(_time)")

    val cols1 = df_disk_filter.columns.toSet
    val cols2 = df_cache.columns.toSet
    val total = (cols1 ++ cols2)

    //    def expr(myCols: Set[String], allCols: Set[String]) = {
    //      allCols.toList.map(x => { if (myCols.contains(x)){F.col(x)} else F.lit(null).as(x)})
    //    }
    //     df_disk_filter.select(expr(cols1, total): _*).union(df_cache.select(expr(cols2, total): _*))
    df_cache.append(df_disk_filter)
  }

  def getException(ex1: Throwable, ex2: Throwable): Throwable =
  {(ex1, ex2) match {
      case (ex1: AnalysisException, _) => CustomException(1, searchId, f"Error in 'read' command", List("read", ex1.getMessage()) )
      case (e1: CustomException, e2: CustomException) =>  CustomException(3, searchId, f"Index not found: $index", List(index))
      case ex => CustomException(3, searchId, f"Error in 'read' command for index=$index", ex._1, List(index, ex._1.getMessage()))
    }
  }

  def search(): DataFrame = {

    log.debug(s"[SearchID:$searchId] IndexSearch: indexPathDisk ='$indexPathDisk'; indexPathCache = '$indexPathCache'")
    var result_disk: Try[DataFrame] = Failure(CustomException(-1, searchId, "Time window is in cache_duration"))
    val delta = System.currentTimeMillis - tws.toLong * 1000
    val duration_cache_millis = duration_cache * 1000
    log.info(s"[SearchID:$searchId] IndexSearch: indexPathDisk ='$indexPathDisk'; indexPathCache = '$indexPathCache'; tws = '${tws.toLong}'; delta = '$delta'; duration_cache_millis = '$duration_cache_millis'")
    if (delta >= duration_cache_millis) {
      val search_disk = new FileSystemSearch(spark, log, searchId, fieldsUsedInFullQuery, fs_disk, indexPathDisk, index, sql, tws.toLong, twf.toLong, preview, fullReadFlag = fullReadFlag)
      result_disk = search_disk.search()
    }
    val search_cache = new FileSystemSearch(spark, log, searchId, fieldsUsedInFullQuery, fs_cache, indexPathCache, index, sql, tws.toLong, twf.toLong, preview, isCache = true, fullReadFlag = fullReadFlag)
    val result_cache = search_cache.search()
    (result_disk, result_cache) match {
      case (Success(disk), Success(cache)) =>{getMergedDf(disk, cache) }
      case (Failure(disk), Success(cache)) => cache
      case (Success(disk), Failure(cache)) => disk
      case (Failure(ex_disk), Failure(ex_cache)) => { throw getException(ex_disk, ex_cache)}//getException(ex_disk, ex_cache) 
    }
  }
}
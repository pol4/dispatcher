package ot.scalaotl
package commands

import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Column, DataFrame, functions => F}
import org.apache.spark.sql.types.{NullType, StringType}
import org.json4s._
import org.json4s.native.JsonMethods._
import ot.scalaotl.config.OTLIndexes
import ot.scalaotl.extensions.DataFrameExt._
import ot.scalaotl.extensions.StringExt._
import ot.scalaotl.parsers.{ExpressionParser, WildcardParser}
import ot.scalaotl.utils.searchinternals._

import scala.collection.mutable.ListBuffer

/** =Abstract=
 * This class provides support of __'''search'''__ otl command.
 *
 * __'''search'''__ used for read raw data from indexes (only fields _raw and time)
 * field _raw is used for search-time-field-extraction
 * The command can search for given words inside the _raw field
 * Search words can be combined via logical operations AND and OR
 *
 * =Usage example=
 * OTL: one index and one search word
 * {{{ search index="index_name" "search_word" | ... other otl-commands }}}
 * OTL: two indexes and one search word
 * {{{ search index="first_index_name" index="second_index_name" "first_search_word" | ... other otl-commands }}}
 * OTL: one index and two search words
 * {{{ search index="index_name" "first_search_word" AND "second_search_word" | ... other otl-commands }}}
 *
 *  Also search command can be used in subqueries
 *
 * @constructor creates new instance of [[OTLRead]]
 * @param sq [[SimpleQuery]]
 */
class RawRead(sq: SimpleQuery) extends OTLBaseCommand(sq) with OTLIndexes with ExpressionParser with WildcardParser {
  val requiredKeywords = Set.empty[String]
  val optionalKeywords: Set[String] = Set("limit", "subsearch")

  val SimpleQuery(_args, searchId, cache, subsearches, tws, twf, stfe, preview) = sq

  // Command has no optional keywords, nothing to validate
  override def validateOptionalKeywords(): Unit = ()

  def jsonStrToMap(jsonStr: String): Map[String, Map[String, String]] = {
    implicit val formats: DefaultFormats.type = org.json4s.DefaultFormats
    parse(jsonStr).extract[Map[String, Map[String, String]]]
  }
  //  println(_args)
  // Get a list of available indexes (allIndexes)
  val allIndexes: ListBuffer[String] = getAllIndexes()
  // Search in the query for all indexes containing * and look for similar ones among the available indexes
  var indexQueriesMap: Map[String, Map[String, String]] = jsonStrToMap(excludeKeywords(_args.trim, List(Keyword("limit", "t"))))
  for (index <- indexQueriesMap) {
    if (index._1.contains("*")) {
      val regex_raw = index._1.replace("*", ".*").r
      log.debug(s"[SearchID:$searchId] filterRegex for maskIndex : $regex_raw")
      val mask_indexes = allIndexes filter (x => regex_raw.pattern.matcher(x).matches())
      log.debug(s"[SearchID:$searchId] maskIndexes : $mask_indexes")
      indexQueriesMap -= index._1
      mask_indexes.foreach(x => indexQueriesMap = indexQueriesMap + (x -> index._2))
    }
  }

  // Get a list of fields used in query (this list next used for build fieldsUsedInFullQuery)
  override val fieldsUsed: List[String] = indexQueriesMap.map {
    case (_, singleIndexMap) => singleIndexMap.getOrElse("query", "").withKeepQuotedText[List[String]](
      (s: String) => """(?![!(])(\S*?)\s*(=|>|<|like|rlike)\s*""".r.findAllIn(s).matchData.map(_.group(1)).toList
    )
      .map(_.strip("!").strip("'").strip("\"").stripBackticks().addSurroundedBackticks)
  }.toList.flatten

  /** Modifies the query in several steps
   * Step 1. Replaces all quotes around fieldnames to backticks.
   * Step 2. Replaces fieldname="null" substrings to 'fieldname is null'
   *         and fieldname!="null" substrings to 'fieldname is not null'
   * Fieldnames are taken from `fieldsUsedInFullQuery`
   *
   * @param i [[ String, Map[String, String] ]] - item with original query
   * @return [[ String ]] - modified item
   */
  def getModifedQuery(i: (String, Map[String, String])): String = {
    val query = i._2.getOrElse("query", "")
    fieldsUsedInFullQuery.foldLeft(query)((q, field) => {
      val nf = field.addSurroundedBackticks
      q.replaceAll("""(\(| )(['`]*\Q""" + field + """\E['`]*)\s*(=|>|<|!=| like| rlike)""", s"$$1$nf$$3")
        .replace(nf + "=\"null\"", s"$nf is null")
        .replace(nf + "!=\"null\"", s"$nf is not null")
    })
  }

  /**
   * Reads data from indexes
   * Determines which fields can be obtained from indexes and which cannot
   * Columns not found are added as null columns
   *
   * @param query [[Map[String, Map[String, String]]] - source query
   * @return [[DataFrame]] - dataframe with index time fields
   */
  private def searchMap(query: Map[String, Map[String, String]]): DataFrame = {
    val (df, allExceptions) = query.foldLeft((spark.emptyDataFrame, List[Exception]())) {
      case (accum, item) =>
        log.debug(s"[SearchID:$searchId]Query is " + item)
        //println (item)
        val modifiedQuery = getModifedQuery(item)
        val nItem = (item._1, item._2 + ("query" -> modifiedQuery))
        //println(nItem)
        log.debug(s"[SearchID:$searchId]Modified query is" + nItem)

        val s = new IndexSearch(spark, log, nItem, searchId, Seq[String](), preview)
        try {
          val fdfe: DataFrame = extractFields(s.search())
          val ifdfe = if (fieldsUsedInFullQuery.contains("index"))
            fdfe.drop("index").withColumn("index", lit(item._1))
          else
            fdfe
          val fdf :DataFrame = if (modifiedQuery == "") ifdfe else ifdfe.filter(modifiedQuery)
          val cols1 = fdf.columns.map(_.stripBackticks().addSurroundedBackticks).toSet
          val cols2 = accum._1.columns.map(_.stripBackticks().addSurroundedBackticks).toSet
          val totalCols = (cols1 ++ cols2).toList
          //          print(s"total cols ${totalCols} | cols1 ${cols1} | cols2 ${cols2}")
          def expr(myCols: Set[String], allCols: Set[String]): List[Column] = {
            allCols.toList.map(x => if (myCols.contains(x)) F.col(x).as(x.stripBackticks()) else F.lit(null).as(x.stripBackticks()))
          }

          if (totalCols.nonEmpty) (fdf.select(expr(cols1, totalCols.toSet): _*).union(accum._1.select(expr(cols2, totalCols.toSet): _*)), accum._2)
          else accum
        } catch {
          case ex: Exception => (accum._1, ex +: accum._2)
        }
    }
    if (query.size == allExceptions.size) throw allExceptions.head


    //    println(s"used in query $fieldsUsedInFullQuery")
    //    println(s"df  ${df.columns.toList.mkString(",")} | ${df.notNullColumns.toList.mkString(",")}")
    //    println(df.show)
    // println(df.schema)
    // Add columns which are used in query but does not exist in dataframe after read (as null values)
    val emptyCols = fieldsUsedInFullQuery
      .map(_.stripBackticks())
      .distinct
      .filterNot(_.contains("*"))
      .diff(df.columns.toSeq)
    log.debug(s"""[SearchID:$searchId] Add null cols to dataframe: [${emptyCols.mkString(", ")}]""")
    emptyCols.foldLeft(df) {
      (acc, col) => acc.withColumn(col, F.lit(null))
      //      (acc, col) => acc.withColumn(col, F.lit(null)).withColumn(col, F.col(col).cast(NullType))
    }
    //    println(df.show)
    //println(df.schema)
    //println(s"df  ${df.columns.toList.mkString(",")} | ${df.notNullColumns.toList.mkString(",")}")
    df
  }

  /**
   * Finds fields from the query that are not among the non-empty fields of the dataframe
   * Makes field extraction for these search-time-field-extraction Fields (by calling makeFieldExtraction)
   * Adds extracted columns to dataframe
   * Extracting algorithms are described in the FieldExtractor class
   *
   * @param df [[DataFrame]] - source dataframe
   * @return [[DataFrame]] - dataframe with search time fields
   */
  private def extractFields(df: DataFrame): DataFrame = {
    import ot.scalaotl.static.FieldExtractor
    //    println(s"df  ${df.columns.toList.mkString(",")} | ${df.notNullColumns.toList.mkString(",")}")
    //        println(df.show)
    //        println(df.schema)
    val stfeFields = fieldsUsedInFullQuery.diff(df.notNullColumns)
    //    println(stfeFields.toList)
    log.debug(s"[SearchID:$searchId] Search-time field extraction: $stfeFields")
    val feDf = makeFieldExtraction(df, stfeFields, FieldExtractor.extractUDF)
    //    println(s"feFf  ${feDf.columns.toList.mkString(",")} | ${feDf.notNullColumns.toList.mkString(",")}")
    feDf

  }

  /**
   * Makes field extraction for these search-time-field-extraction Fields
   * And adds them to dataframe
   *
   * @param df [[DataFrame]] - source dataframe
   * @param extractedFields [[ Seq[String] ]] - list of fields for search-time-field-extraction
   * @param udf [[ UserDefinedFunction ]] - UDF-function for fields extraction
   * @return [[DataFrame]] - dataframe with search time fields
   */
  private def makeFieldExtraction(df: DataFrame, extractedFields: Seq[String], udf: UserDefinedFunction): DataFrame = {
    import org.apache.spark.sql.functions.{col, expr}
    val stfeFieldsStr = extractedFields.map(x => s""""${x.replaceAll("\\{(\\d+)}", "{}")}"""").mkString(", ")
    val mdf = df.withColumn("__fields__", expr(s"""array($stfeFieldsStr)"""))
      .withColumn("stfe", udf(col("_raw"), col("__fields__")))
    val fields: Seq[String] = if (extractedFields.exists(_.contains("*"))) {
      val sdf = mdf.agg(flatten(collect_set(map_keys(col("stfe")))).as("__schema__"))
      sdf.first.getAs[Seq[String]](0)
    } else extractedFields
    //    println(s"mdf  ${mdf.columns.toList.mkString(",")} | ${mdf.notNullColumns.toList.mkString(",")}")
    //    println(fields)
    val existedFields = mdf.notNullColumns
    fields.foldLeft(mdf) { (acc, f) => {
      if (!existedFields.contains(f)) {
        if (f.contains("{}"))
          acc.withColumn(f, col("stfe")(f))
        else {
          val m = "\\{(\\d+)}".r.pattern.matcher(f)
          var index = if (m.find()) m.group(1).toInt - 1 else 0
          index = if (index < 0) 0 else index
          acc.withColumn(f, col("stfe")(f.replaceFirst("\\{\\d+}", "{}"))(index))
        //          if (m.matches()) {
        //            var index = if (m.find()) m.group(1).toInt - 1 else 0
        //            index = if (index < 0) 0 else index
        //            acc.withColumn(f, col("stfe")(f.replaceFirst("\\{\\d+}", "{}"))(index))
        //          }
        //          else acc.withColumn(f, F.lit(null)).withColumn(f, F.col(f).cast(NullType))
        }
      } else acc
    }
    }.drop("__fields__", "stfe")
  }

  /**
   * Standard method called by [[Converter]] in each command.
   *
   * @param _df [[DataFrame]] - incoming dataset (in generator-command like this one is ignored and should be empty)
   * @return [[DataFrame]]  - outgoing dataset
   */
  override def transform(_df: DataFrame): DataFrame = {
    log.debug(s"searchId = $searchId queryMap: $indexQueriesMap")
    val dfInit = searchMap(indexQueriesMap)
    val dfLimit = getKeyword("limit") match {
      case Some(lim) => log.debug(s"[SearchID:$searchId] Dataframe is limited to $lim"); dfInit.limit(100000)
      case _ => dfInit
    }
    val dfStfe = dfLimit
//    println("Transform print df")
//    dfStfe.show()
//    println(dfStfe.schema)
    val (df_sub, s): (DataFrame, String) = getKeyword("subsearch") match {
      case Some(str) =>
        cache.get(str) match {
          case Some(jdf) => (jdf, str)
          case None => (null, null)
        }
      case None => (null, null)
    }
    if (df_sub != null)
      println("subsearch")
    val ans: DataFrame = if (df_sub != null) new OTLJoin(SimpleQuery(s"""type=inner max=1 ${df_sub.columns.toList.mkString(",")} subsearch=$s""", cache)).transform(dfStfe)
    else dfStfe
    ans


    //    println(s"transform ${ans.columns.toList.mkString(",")} | ${ans.notNullColumns.toList.mkString(",")}")
    //    ans.show()
    //    val emptyCols = fieldsUsedInFullQuery.diff(ans.notNullColumns)
    //    val ans2 = emptyCols.foldLeft(ans) {
    //      (acc, col) => acc.withColumn(col, F.col(col).cast(StringType))
    //    }
    //    print(ans2.schema)
    //    print(ans.na.fill("null").toJSON.collect().mkString("[\n",",\n","\n]"))
    //    print(ans2.na.fill("null").toJSON.collect().mkString("[\n",",\n","\n]"))


  }
}
package ot.scalaotl
package commands

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.{ col, last, lit,  monotonically_increasing_id }
import ot.scalaotl.extensions.StringExt._
import scala.util.{Try, Success, Failure}

class OTLFilldown(sq: SimpleQuery) extends OTLBaseCommand(sq, _seps = Set("by")) {
  val requiredKeywords= Set.empty[String]
  val optionalKeywords= Set.empty[String]
  override def transform(_df: DataFrame): DataFrame = {

    val groups  = positionalsMap.get("by") match {
      case Some(Positional("by", groups)) => groups
      case _ => List("__internal__")
    }

    val by = if (groups.isEmpty) {"__internal__"} else {groups.head.stripBackticks()}

    val ws = Window.partitionBy(by).orderBy("__idx__").rowsBetween(Window.unboundedPreceding, Window.currentRow)
    val filldownColumns = returns.flatFields.map(_.stripBackticks()).intersect(_df.columns)
    log.debug(s"filldownColumns $filldownColumns")
    val df_grouped  = _df.withColumn("__internal__", lit(0))
    filldownColumns.foldLeft(df_grouped.withColumn("__idx__", monotonically_increasing_id)) {
      case (accum, item) => accum.withColumn(item, last(col(item), ignoreNulls = true).over(ws))
    }
      .drop("__idx__", "__internal__")
  }
}

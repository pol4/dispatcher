package ot.scalaotl
package commands

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.{col, last, lit, monotonically_increasing_id}
import ot.scalaotl.extensions.StringExt._

/** =Abstract=
 * This class provides support of __'''filldown'''__ otl command.
 *
 * __'''filldown'''__ changes __NULL__ values with the last non-null value for a field
 *
 * __'''filldown'''__ takes two optional argument:
 *
 *    1.  _'''wc-field-list'''_ - comma-separated field names to which the command will apply. If this param
 *    is empty, than command will apply to all NULL-value-containing fields
 *    2. __'''by'''__ - defines the field by which grouping occurs in the table to further replace
 *    the null values of the fields specified in the _'''wc-field-list'''_ parameter separately for each group.
 *
 * Note: if _'''by'''_ is used, the parameter _'''wc-field-list'''_ should be specified.
 *
 * =Usage examples=
 * * OTL 1:
 * {{{| makeresults | eval a = mvappend(1,2,null,3), b ="A" | mvexpand a | append [makeresults | eval a = null,b="B"]
 * | append [makeresults | eval a=4,b="A"] | append [makeresults | eval a=100,b="C"] | append [makeresults | eval a=null,b="A"]
 * | append [makeresults | eval a = null,b="C"]  | append [makeresults | eval a = 5,b=null]  | sort b | filldown}}}
 * Result:
 * {{{+----------+---+---+
|     _time|  b|  a|
+----------+---+---+
|1650027083	| A|1|
|1650027083	| A|2|
|1650027083	| A|2|
|1650027083	| A|2|
|1650027083	| A|3|
|1650027082 | A|4|
|1650027082 | B|4|
|1650027082 | C|4|
|1650027083 | C|100|
|1650027081 | C|5|
+----------+---+---+}}}
 * OTL 2:
 * {{{| makeresults | eval a = mvappend(1,2,null,3), b ="A" | mvexpand a | append [makeresults | eval a = null,b="B"]
 * | append [makeresults | eval a=4,b="A"] | append [makeresults | eval a=100,b="C"] | append [makeresults | eval a=null,b="A"]
 * | append [makeresults | eval a = null,b="C"]  | sort b | filldown a}}}
 * Result:
 * {{{+----------+---+---+
|     _time|  b|  a|
+----------+---+---+
|1650027175	| A|1|
|1650027175	| A|2|
|1650027175	| A|2|
|1650027175	| A|2|
|1650027174	| A|3|
|1650027174 | A|4|
|1650027173 | B|4|
|1650027173 | C|4|
|1650027174 | C|100|
+----------+---+---+}}}
 * OTL 3:
 * {{{| makeresults | eval a = mvappend(1,2,null,3), b ="A" | mvexpand a | append [makeresults | eval a = null,b="B"]
 * | append [makeresults | eval a=4,b="A"] | append [makeresults | eval a=100,b="C"] | append [makeresults | eval a=null,b="A"]
 * | append [makeresults | eval a = null,b="C"]  | sort b | filldown a by b}}}
 * Result:
 * {{{+----------+---+---+
|     _time|  b|  a|
+----------+---+---+
|1650026521	| B| |
|1650026521	| C| |
|1650026521	| C|100|
|1650026523	| A|1|
|1650026523	| A|2|
|1650026523 | A|2|
|1650026523 | A|3|
|1650026521 | A|4|
|1650026521 | A|4|
+----------+---+---+}}}
 * @constructor creates new instance of [[OTLFilldown]]
 * @param sq [[SimpleQuery]]
 */
class OTLFilldown(sq: SimpleQuery) extends OTLBaseCommand(sq, _seps = Set("by")){
  val requiredKeywords = Set.empty[String]
  val optionalKeywords = Set.empty[String]

  /**
   * @param _df input __dataframe__, passed by the [[Converter]] when executing an OTL query
   * @return _df with events combined by specified field
   */
  override def transform(_df: DataFrame): DataFrame = {
    //Define field for grouping. If by-param not exists, this field is fictive.
    val groups = positionalsMap.get("by") match {
      case Some(Positional("by", groups)) => groups
      case _ => List("__internal__")
    }
    val by = if (groups.isEmpty) {
      "__internal__"
    } else {
      groups.head.stripBackticks()
    }
    //Define fields for null replacing
    val dfColumns = _df.columns
    val fields = if (returns.flatFields.isEmpty) {
      dfColumns.filter(c => !(_df.select(col(c)).filter(row => row.isNullAt(0)).isEmpty)).toList
    } else {
      returns.flatFields
    }
    val filldownColumns = fields.map(_.stripBackticks()).intersect(dfColumns)
    log.debug(s"filldownColumns $filldownColumns")
    val df_grouped = _df.withColumn("__internal__", lit(0))
    //Window with ordering for grouping by by-param
    val ws = Window.partitionBy(by).orderBy("__idx__").rowsBetween(Window.unboundedPreceding, Window.currentRow)
    //Replace null values in filldown columns
    filldownColumns.foldLeft(df_grouped.withColumn("__idx__", monotonically_increasing_id)) {
      case (accum, item) => {
        val column = last(col(item), ignoreNulls = true).over(ws)
        accum.withColumn(item, column)
      }
    }
      .drop("__idx__", "__internal__")
  }
}

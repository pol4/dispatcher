package ot.scalaotl
package static

import org.apache.spark.sql.functions.udf
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.util.matching.Regex

class OtJsonParser extends Serializable {
  implicit val formats = DefaultFormats

  def parseTags(tag: String): (Option[String], Option[String]) = {
    // parse "tagname{idx}" structure
    val rexTag = """([^\{\}]*)(\{(\d+)\})?""".r
    rexTag.findFirstMatchIn(tag).map(x => (x.group(1), x.group(3))) match {
      case Some((str, idx)) => (Option(str), Option(idx))
      case _                => (None, None)
    }
  }

  def parseSpath = (jsonStr: String, spath: String) => {
    val json = parse(jsonStr)
    spath.split("\\.").foldLeft(json) {
      case (parsedJson, tag) => {
        parseTags(tag) match {
          case (Some(t), Some(idx)) => (parsedJson \ t)(idx.toInt)
          case (Some(t), None)      => parsedJson \ t
          case _                    => parsedJson
        }
      }
    }.values match {
      case any: List[Any] => any.map(_.toString).headOption.getOrElse("first item")
      case any            => any.toString
    }
  }

  def flattenWithFilter(js: Any, prefix: String = "", regexes: List[Regex] ): Map[String, String] = {
    js match {
      case map: Map[_,_] => map.map(i => flattenWithFilter(i._2, if(prefix == "") i._1.toString else prefix + "."+  i._1, regexes)).flatten.toMap
      case seq: List[_] => seq.zipWithIndex.flatMap { case (x, i) => flattenWithFilter(x,prefix + s"{$i}", regexes) }.toMap
      case value: AnyRef => if(regexes.exists(_.pattern.matcher(prefix).matches())) Map(prefix -> value.toString) else Map.empty
      case _ => Map(prefix -> null)
    }
  }

  def escapeChars(s: String, escapedCharList: String): String = {
    escapedCharList.filter(s.contains(_)).foldLeft(s) {
      case(acc, char) => acc.replaceAllLiterally(s"$char", s"\\$char")
    }
  }

  def getFiltered(fieldMap: Map[String, String], spaths : List[String]) = {
    val max_cols = 200
    val max_mv_size = 50
    var i = 0
    val regexes = spaths.map(_.replace("{}","\\{\\d+\\}").r)
    fieldMap.filter{case (k,v) => regexes.exists(_.pattern.matcher(k).matches())}
  }

  /**
   * Parse input 1-dimension json key-value set and find value confirmations for set of field names
   * @param jsonStr input json
   * @param spaths set of fields
   * @param withNotExistingFields do synthetic confirmation for fields from set which haven't confirms in parsed json (with nulls)
   * @return
   */
  def parseSpaths(jsonStr: String, spaths: Set[String], withNotExistingFields:Boolean): Map[String, String] = {
    val json = parse(jsonStr)
    val extracted = json.extract[Map[String, Any]]
    val paths: List[String] = (if (!withNotExistingFields) {
      //in this case spaths filtering by confirming to json extracted keys, including multi-value cases
      val extractedKeys = extracted.keys.toSet
      val bracketedExtractedKeys = extractedKeys.map(_ + "{}")
      val allKeys = extractedKeys.union(bracketedExtractedKeys)
      var accumPaths = spaths.intersect(allKeys)
      val dottedSpaths = spaths.filter(_.contains("."))
      accumPaths ++= dottedSpaths.filter(dsp => dsp.zipWithIndex.filter(_._1 == '.').map(_._2).map(dsp.substring(0, _)).exists(allKeys.contains))
      accumPaths
    }
    else
      spaths).toList.distinct
    //paths ++= extractedKeys.filter(ek => ek.contains(".") && spaths.contains(ek.substring(0, ek.indexOf(".") - 1)))
    //val flattened =
    flattenWithFilter(extracted, "", paths.map(_.replace("{}", "\\{\\d+\\}").replace("*", ".*").r)
      .toList);
    //    flattened.toList.groupBy(_._1.replaceAll("\\{\\d+\\}","{}")).map{case (k,v) => (k, v.sortBy(_._1).map(_._2))}
    //      .filter(x => x._1.contains("{}") || !(x._2.length > 1))
  }
}

object OtJsonParser {
  @transient lazy val jp = new OtJsonParser()
  def spathUDF = udf((jstr: String, path: String) => jp.parseSpath(jstr, path))
}
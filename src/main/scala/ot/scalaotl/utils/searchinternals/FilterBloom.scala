package ot.scalaotl
package utils
package searchinternals

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.util.sketch.BloomFilter
import ot.AppConfig.{config, getLogLevel}

import scala.collection.mutable.ListBuffer

object FilterBloom {

  val log: Logger = Logger.getLogger("FilterBloom")
  log.setLevel(Level.toLevel(getLogLevel(config, "FilterBloom")))
  
  private def escapeRegex(s:String): String =
  {
    s.replace("{", "\\{").replace("}","\\}")
  }


  private def isTokensInFb(bucket: String, tokens_raw: ListBuffer[String], tokens_cols: ListBuffer[String],
                           query: String, fs: FileSystem, indexPath: String, indexName: String, bloomFileName: String): Boolean = {
    try {
      val status = fs.listStatus(new Path(s"$indexPath$indexName/$bucket/$bloomFileName"))
      val filenames = ListBuffer[String]()
      status.foreach(x ⇒ filenames += x.getPath().toString())
      val path = new Path(filenames(0))
      val stream = fs.open(path)
      val bloom = BloomFilter.readFrom(stream)
      var logical_query = query
      for (token ← tokens_raw) {
        val isTokenContains = bloom.mightContain(token.toLowerCase()).toString().toLowerCase()
        val token_escaped =  escapeRegex(token)
        logical_query = logical_query.replaceAll(s"""`_raw` like (\"|\')%$token_escaped%(\'|\")""", isTokenContains)
      }
      for (token ← tokens_cols) {
        val system_cols = List("source", "sourcetype", "host")
        var isTokenContains = bloom.mightContain(token).toString().toLowerCase()
        if (system_cols.contains(token)) isTokenContains="true"
        val token_escaped =  escapeRegex(token)
        log.debug(s"Transformation: $token_escaped $logical_query")
        logical_query = logical_query.replaceAll(s"""`*$token_escaped`*(=|!=|<|<=|>|>=)(\"(.*?)\"|\\d+(?:\\.\\d+)*|[a-zA-Zа-яА-Я0-9_*-]+)""", isTokenContains)
      }
      logical_query = logical_query.replaceAll("""\s+AND\s+""", " & ")
      logical_query = logical_query.replaceAll("""\s+OR\s+""", " | ")
      log.debug(s"Logical Query: $logical_query")
      val result = BooleanEvaluator.evaluate(logical_query)
      log.debug(s"Bucket: $bucket; result: $result")
      stream.close()
      return result
    } catch { case e: Exception => log.debug(s"Exception $e in $bucket, append to search"); return true}
    true
  }

  private def transormQuery(query: String): String = 
  {
    val regex_comparison = """!\(`[a-zA-Zа-яА-Я0-9_*-.{}]+`=([a-zA-Z0-9_*-]+|\"(.*?)\"|\d+(?:\.\d+)*)\)"""
    val transform_query = query.replaceAll(regex_comparison, "true")
    val regex_rlike = """\`(.*?)\` rlike (\"|\')(.*?)(\'|\")"""
    val result = transform_query.replaceAll(regex_rlike, "true")
    result
  }

  private def getTokens(regex: String, query: String, group: Int): ListBuffer[String] =
  {
    val mi = regex.r.findAllMatchIn(query)
    var tokens = ListBuffer[String]()
    while (mi.hasNext) {
      val d = mi.next
      tokens += d.group(group)
    }
    tokens
  }

  def getBucketsByFB(fs: FileSystem, indexPath: String, indexName: String, bloomFileName: String, buckets: ListBuffer[String], query: String): ListBuffer[String] = {
    log.debug(s"FilterBloom; Query: $query")
    val regex_raw = """`_raw` like (\"|\')%(.*?)%(\'|\")"""
    val regex_col = """`*([a-zA-Zа-яА-Я0-9_*-.{}]+)`*(=|!=|<|<=|>|>=)(\"(.*?)\"|\d+(?:\.\d+)*|[a-zA-Z0-9_*-]+)"""
    val tokens_raw =  getTokens(regex_raw, query, 2)
    val tokens_cols = getTokens(regex_col, query, 1)
    var resultbuckets = buckets
    val transform_query = transormQuery(query)
    if (tokens_raw.nonEmpty)
      log.debug(s"Tokens for FTS: $tokens_raw")
      log.debug(s"Tokens for column search: $tokens_cols")
      resultbuckets = buckets.filter(isTokensInFb(_, tokens_raw, tokens_cols, transform_query, fs, indexPath, indexName, bloomFileName))
    resultbuckets
  }
}

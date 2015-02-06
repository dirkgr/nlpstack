package org.allenai.nlpstack.parse.poly.polyparser

import org.allenai.nlpstack.parse.poly.eval.{
  PathAccuracy,
  UnlabeledBreadcrumbAccuracy,
  ParseEvaluator
}
import org.allenai.nlpstack.parse.poly.fsm.{
  ClassifierBasedCostFunction,
  RerankingFunction,
  FeatureUnion
}
import scopt.OptionParser

import scala.compat.Platform

private case class ParseFileConfig(configFilename: String = "", testFilename: String = "",
  dataSource: String = "", oracleNbest: Int = ParseFile.defaultOracleNbest)

object ParseFile {

  val defaultOracleNbest = 50

  /** Command-line for parsing a test set in CoNLL format.
    *
    * Usage: ParseFile [options]
    *
    * -c <file> | --config <file>
    * the file containing the JSON  configuration for the parser
    * -t <file> | --test <file>
    * the file containing the test parses to parse and compare against (CoNLL-X format)
    * -d <file> | --datasource <file>
    * the location of the data ('datastore','local')
    *
    * @param args see above
    */
  def main(args: Array[String]) {
    val optionParser = new OptionParser[ParseFileConfig]("ParseFile") {
      opt[String]('c', "config") required () valueName ("<file>") action
        { (x, c) => c.copy(configFilename = x) } text ("the file containing the JSON " +
          " configuration for the parser")
      opt[String]('t', "test") required () valueName ("<file>") action
        { (x, c) => c.copy(testFilename = x) } text ("the file containing the test " +
          "parses to parse and compare against (in ConllX format, comma-separated filenames)")
      opt[String]('d', "datasource") required () valueName ("<file>") action
        { (x, c) => c.copy(dataSource = x) } text ("the location of the data " +
          "('datastore','local')") validate { x =>
            if (Set("datastore", "local").contains(x)) {
              success
            } else {
              failure(s"unsupported data source: ${x}")
            }
          }
      opt[Int]('o', "oraclenbest") required () valueName ("<int>") action
        { (x, c) => c.copy(oracleNbest = x) } text ("n-best list size for oracle evaluation")
    }
    val config: ParseFileConfig = optionParser.parse(args, ParseFileConfig()).get
    val parser: TransitionParser = TransitionParser.load(config.configFilename)
    fullParseEvaluation(parser, config.testFilename, ConllX(true), config.dataSource,
      config.oracleNbest)
  }

  /** Re-parses a sequence of parses, and compares the results
    * to the gold standard.
    *
    * @param parser the parser we want to use to parse the sentences
    * @param parseSource the gold parses
    */
  def parseTestSet(parser: TransitionParser, parseSource: PolytreeParseSource): Unit = {
    println("Parsing test set.")
    val startTime: Long = Platform.currentTime
    val candidateParses: Iterator[Option[PolytreeParse]] = {
      parseSource.parseIterator map {
        parse => parser.parse(parse.sentence)
      }
    }
    val stat = UnlabeledBreadcrumbAccuracy
    stat.reset()
    PathAccuracy.reset()
    ParseEvaluator.evaluate(candidateParses, parseSource.parseIterator,
      Set(stat, PathAccuracy))
    val parsingDurationInSeconds: Double = (Platform.currentTime - startTime) / 1000.0
    println("Parsed %d sentences in %.1f seconds, an average of %.1f sentences per second.".format(
      stat.numParses, parsingDurationInSeconds, (1.0 * stat.numParses) / parsingDurationInSeconds
    ))
  }

  /** Determines the oracle score for n-best parsing a sequence of parses.
    *
    * @param parser the parser to use
    * @param parseSource data source for the gold parses to check against
    * @param oracleNbestSize size of the n-best lists generated by the parser
    */
  def oracleParseTestSet(
    parser: TransitionParser,
    parseSource: PolytreeParseSource, oracleNbestSize: Int
  ): Unit = {

    parser match {
      case rerankingParser: RerankingTransitionParser =>
        val oracleRerankingFunction: RerankingFunction =
          OracleRerankingFunction(parseSource.parseIterator)
        val oracleParserConfig = ParserConfiguration(
          rerankingParser.config.parsingCostFunction,
          //parserConfig.labelingCostFunction,
          oracleRerankingFunction, oracleNbestSize
        )
        val parser = RerankingTransitionParser(oracleParserConfig)
        parseTestSet(parser, parseSource)
      case _ =>
        println("Must use RerankingTransitionParser to do oracle parsing.")
    }
  }

  /** Convenience method for doing a full evaluation of a parsing model on a set of test parses.
    *
    * @param parser the parser to use
    * @param testFiles (comma-separated) filenames of the gold parse files to test the parser on
    * @param testFileFormat file format of the gold parse files
    * @param dataSource where to find the files (i.e. "datastore" or "local")
    * @param oracleNbestSize size of the n-best lists to generate for oracle scoring
    */
  def fullParseEvaluation(parser: TransitionParser, testFiles: String,
    testFileFormat: PolytreeParseFileFormat, dataSource: String,
    oracleNbestSize: Int): Unit = {

    val testSources: Map[String, PolytreeParseSource] =
      (testFiles.split(",") map { path =>
        (path, FileBasedPolytreeParseSource.getParseSource(
          path,
          testFileFormat, dataSource
        ))
      }).toMap
    for ((sourcePath, testSource) <- testSources) {
      println(s"Checking parser accuracy on test set ${sourcePath}.")
      ParseFile.parseTestSet(parser, testSource)

      println(s"Checking oracle accuracy on test set ${sourcePath}.")
      oracleParseTestSet(parser, testSource, oracleNbestSize)
    }
  }
}

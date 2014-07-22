package org.allenai.nlpstack.coref

import org.allenai.common.testkit.UnitSpec
import org.allenai.nlpstack.core.parse.graph.DependencyGraph
import org.allenai.nlpstack.core.PostaggedToken
import org.allenai.nlpstack.postag.defaultPostagger
import org.allenai.nlpstack.tokenize.defaultTokenizer
import org.allenai.nlpstack.parse.defaultDependencyParser

class FactorieCorefResolverSpec extends UnitSpec {
  val resolver = new FactorieCorefResolver[PostaggedToken]

  "FactorieCorefResolver" should "resolve coreferences" in {

    val text = "Centaurs have two rib cages."
    val tokens = defaultTokenizer.tokenize(text)
    val postaggedTokens = defaultPostagger.postagTokenized(tokens)
    val parseTree = defaultDependencyParser.dependencyGraphPostagged(postaggedTokens)

    val referents = resolver.resolveCoreferences((postaggedTokens, parseTree))
  }
}

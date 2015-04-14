package org.allenai.nlpstack.parse.poly.core

import org.allenai.nlpstack.core.{ Token => NLPStackToken, Lemmatized, PostaggedToken, Tokenizer }
import org.allenai.nlpstack.parse.poly.ml.{ BrownClusters, Verbnet }
import org.allenai.nlpstack.postag._
import org.allenai.nlpstack.lemmatize._

import reming.DefaultJsonProtocol._

trait SentenceTransform {
  def transform(sentence: Sentence): Sentence
}

object SentenceTransform {
  private implicit val factorieSentenceTaggerFormat = jsonFormat0(() => FactorieSentenceTagger)
  private implicit val stanfordSentenceTaggerFormat = jsonFormat0(() => StanfordSentenceTagger)
  private implicit val lexicalPropertiesTaggerFormat = jsonFormat0(() => LexicalPropertiesTagger)
  private implicit val brownClustersTaggerFormat = jsonFormat1(BrownClustersTagger.apply)
  private implicit val verbnetTaggerFormat = jsonFormat1(VerbnetTagger.apply)

  implicit val sentenceTransformJsonFormat = parentFormat[SentenceTransform](
    childFormat[FactorieSentenceTagger.type, SentenceTransform],
    childFormat[StanfordSentenceTagger.type, SentenceTransform],
    childFormat[LexicalPropertiesTagger.type, SentenceTransform],
    childFormat[BrownClustersTagger, SentenceTransform],
    childFormat[VerbnetTagger, SentenceTransform]
  )
}

/** The FactorieSentenceTagger tags an input sentence with automatic part-of-speech tags
  * from the Factorie tagger.
  */
case object FactorieSentenceTagger extends SentenceTransform {

  def transform(sentence: Sentence): Sentence = {
    val words: IndexedSeq[String] = sentence.tokens.tail map { tok => tok.word.name }
    val nlpStackTokens: IndexedSeq[NLPStackToken] =
      Tokenizer.computeOffsets(words, words.mkString).toIndexedSeq
    val taggedTokens: IndexedSeq[PostaggedToken] =
      defaultPostagger.postagTokenized(nlpStackTokens).toIndexedSeq
    Sentence(NexusToken +: (taggedTokens.zip(sentence.tokens.tail) map {
      case (tagged, untagged) =>
        val autoPos = Symbol(tagged.postag)
        val autoCpos = Symbol(WordClusters.ptbToUniversalPosTag.getOrElse(
          autoPos.name, "X"
        ))
        untagged.updateProperties(Map(
          'autoPos -> Set(autoPos),
          'autoCpos -> Set(autoCpos)
        ))
    }))
  }
}

/** The StanfordSentenceTagger tags an input sentence with automatic part-of-speech tags
  * from the Stanford tagger.
  */
case object StanfordSentenceTagger extends SentenceTransform {

  @transient private val stanfordTagger = new StanfordPostagger()

  def transform(sentence: Sentence): Sentence = {
    val words: IndexedSeq[String] = sentence.tokens.tail map { tok => tok.word.name }
    val nlpStackTokens: IndexedSeq[NLPStackToken] =
      Tokenizer.computeOffsets(words, words.mkString).toIndexedSeq
    val taggedTokens: IndexedSeq[PostaggedToken] =
      stanfordTagger.postagTokenized(nlpStackTokens).toIndexedSeq
    Sentence(NexusToken +: (taggedTokens.zip(sentence.tokens.tail) map {
      case (tagged, untagged) =>
        val autoPos = Symbol(tagged.postag)
        val autoCpos = Symbol(WordClusters.ptbToUniversalPosTag.getOrElse(
          autoPos.name, "X"
        ))
        untagged.updateProperties(Map(
          'autoPos -> Set(autoPos),
          'autoCpos -> Set(autoCpos)
        ))
    }))
  }
}

/** The LexicalPropertiesTagger tags the tokens of an input sentence with lexical
  * properties like whether the first letter is capitalized, or whether it contains numeric
  * digits.
  */
case object LexicalPropertiesTagger extends SentenceTransform {

  def transform(sentence: Sentence): Sentence = {
    Sentence(sentence.tokens map { tok =>
      val tokStr = tok.word.name
      val firstLetterCapital = tokStr.headOption match {
        case Some(x) if Character.isUpperCase(x) => Set('firstCap)
        case _ => Set[Symbol]()
      }
      val existsCapital = tokStr match {
        case tokStr: String if tokStr exists { Character.isUpperCase(_) } => Set('existsCap)
        case _ => Set[Symbol]()
      }
      val allCaps = tokStr match {
        case tokStr: String if tokStr forall { Character.isUpperCase(_) } => Set('allCaps)
        case _ => Set[Symbol]()
      }
      val existsNumber = tokStr match {
        case tokStr: String if tokStr exists { Character.isDigit(_) } => Set('existsNum)
        case _ => Set[Symbol]()
      }
      tok.updateProperties(Map(
        'lcase -> Set(Symbol(tokStr.toLowerCase)),
        'lexical -> (firstLetterCapital ++ existsCapital ++ allCaps ++ existsNumber)
      ))
    })
  }
}

/** The BrownClustersTagger tags the tokens of a sentence with their Brown clusters. */
case class BrownClustersTagger(clusters: Seq[BrownClusters]) extends SentenceTransform {

  def transform(sentence: Sentence): Sentence = {
    Sentence(for {
      tok <- sentence.tokens
    } yield tok.updateProperties((for {
      (cluster, clusterId) <- clusters.zipWithIndex
    } yield {
      Symbol(s"brown${clusterId}") ->
        Set(cluster.getMostSpecificCluster(Symbol(tok.word.name.toLowerCase)))
    }).toMap))
  }
}

/** The FactorieLemmatizer tags the tokens of an input sentence with their lemmas, according
  * to the Factorie lemmatizer.
  */
case object FactorieLemmatizer extends SentenceTransform {

  override def transform(sentence: Sentence): Sentence = {
    val words: IndexedSeq[String] = sentence.tokens.tail map { tok => tok.word.name }
    val nlpStackTokens: IndexedSeq[NLPStackToken] =
      Tokenizer.computeOffsets(words, words.mkString).toIndexedSeq
    val taggedTokens: IndexedSeq[PostaggedToken] =
      defaultPostagger.postagTokenized(nlpStackTokens).toIndexedSeq
    val lemmatizedTaggedTokens: IndexedSeq[Lemmatized[PostaggedToken]] =
      taggedTokens map {
        x => Lemmatized[PostaggedToken](x, MorphaStemmer.lemmatize(x.string, x.postag))
      }
    Sentence(NexusToken +: (lemmatizedTaggedTokens.zip(sentence.tokens.tail) map {
      case (tagged, untagged) =>
        untagged.updateProperties(Map(
          'factorieLemma -> Set(Symbol(tagged.lemma))
        ))
    }))
  }
}

/** The VerbnetTagger tags the tokens of an input sentence with their Verbnet classes.
  */
case class VerbnetTagger(verbnet: Verbnet) extends SentenceTransform {
  override def transform(sentence: Sentence): Sentence = {
    val lemmatized = FactorieLemmatizer.transform(sentence)
    Sentence(for {
      tok <- lemmatized.tokens
    } yield {
      val tokLemmaLC = tok.getDeterministicProperty('factorieLemma).name.toLowerCase
      val tokVerbnetPrimaryFrames = verbnet.getVerbnetFramePrimaryNames(tokLemmaLC)
      val tokVerbnetSecondaryFrames = verbnet.getVerbnetFrameSecondaryNames(tokLemmaLC)
      tok.updateProperties(Map('verbnetPrimaryFrames -> tokVerbnetPrimaryFrames))
    })
  }
}

package org.allenai.nlpstack.parse.poly.ml

import org.allenai.nlpstack.parse.poly.decisiontree.{
  FeatureVector => DTFeatureVector,
  FeatureVectorSource => DTFeatureVectorSource,
  _
}
import org.allenai.nlpstack.parse.poly.fsm.SimpleTask
import scala.collection.immutable.HashSet

case class WrapperClassifier(
    classifier: ProbabilisticClassifier,
    featureNameMap: Seq[(Int, FeatureName)]
) {

  @transient
  private val featureNameToIndex: Map[FeatureName, Int] =
    (featureNameMap map {
      case (featIndex, feat) =>
        (feat, featIndex)
    }).toMap

  def classify(featureVector: FeatureVector): Int = {
    classifier.classify(
      WrapperClassifier.createDTFeatureVector(featureVector, featureNameToIndex, None)
    )
  }

  def getDistribution(featureVector: FeatureVector): Map[Int, Double] = {
    classifier.outcomeDistribution(
      WrapperClassifier.createDTFeatureVector(featureVector, featureNameToIndex, None)
    )
  }
}

object WrapperClassifier {
  def createDTFeatureVector(
    featureVector: FeatureVector,
    featureNameToIndex: Map[FeatureName, Int],
    outcome: Option[Int]
  ): DTFeatureVector = {
    val trueAttributeNames: Seq[FeatureName] =
      featureVector.values filter { _._2 != 0 } map { _._1 }
    val trueAttributes: HashSet[Int] =
      HashSet(trueAttributeNames
        .filter(featureNameToIndex.contains)
        .map(featureNameToIndex).toSeq: _*)
    new SparseVector(outcome, featureNameToIndex.values.max + 1, trueAttributes)
  }
}

class WrapperClassifierTrainer(classifierTrainer: ProbabilisticClassifierTrainer) {

  def apply(trainingData: TrainingData): WrapperClassifier = {
    val featureNames: Seq[FeatureName] = trainingData.featureNames.toSeq
    val featureNameToIndex: Map[FeatureName, Int] = featureNames.zipWithIndex.toMap
    val vectors = new InMemoryFeatureVectorSource(
      (trainingData.labeledVectors map {
      case (vec, outcome) =>
        WrapperClassifier.createDTFeatureVector(vec, featureNameToIndex, Some(outcome))
    }).toIndexedSeq,
      SimpleTask("basic")
    )
    println(s"Task has ${vectors.numVectors} training vectors")
    val inducedClassifier: ProbabilisticClassifier = classifierTrainer(vectors)
    val featureMap: Seq[(Int, FeatureName)] =
      featureNames.zipWithIndex filter {
        case (_, featIndex) =>
          inducedClassifier.allFeatures.contains(featIndex)
      } map {
        case (feat, featIndex) =>
          (featIndex, feat)
      }
    WrapperClassifier(inducedClassifier, featureMap)
  }
}

package com.tencent.angel.ml.tree

import com.tencent.angel.ml.core.MLLearner
import com.tencent.angel.ml.feature.LabeledData
import com.tencent.angel.ml.model.MLModel

import scala.util.Random
import com.tencent.angel.ml.tree.model.{DecisionTreeModel, RandomForest, RandomForestModel}
import com.tencent.angel.ml.tree.conf.Algo.{Algo, Classification, Regression}
import com.tencent.angel.ml.tree.conf.QuantileStrategy._
import com.tencent.angel.ml.tree.conf.Strategy
import com.tencent.angel.ml.tree.impurity.Impurities
import com.tencent.angel.worker.storage.DataBlock
import com.tencent.angel.worker.task.TaskContext
import org.apache.commons.logging.LogFactory

import scala.collection.mutable.ArrayBuffer

/**
  * A class that implements a <a href="http://en.wikipedia.org/wiki/Random_forest">Random Forest</a>
  * learning algorithm for classification and regression.
  * It supports both continuous and categorical features.
  *
  * The settings for featureSubsetStrategy are based on the following references:
  *  - log2: tested in Breiman (2001)
  *  - sqrt: recommended by Breiman manual for random forests
  *  - The defaults of sqrt (classification) and onethird (regression) match the R randomForest
  *    package.
  *
  * @see <a href="http://www.stat.berkeley.edu/~breiman/randomforest2001.pdf">Breiman (2001)</a>
  * @see <a href="http://www.stat.berkeley.edu/~breiman/Using_random_forests_V3.1.pdf">
  * Breiman manual for random forests</a>
  * @param ctx      Task context of Angel
  * @param strategy The configuration parameters for the random forest algorithm which specify
  *                 the type of random forest (classification or regression), feature type
  *                 (continuous, categorical), depth of the tree, quantile calculation strategy,
  *                 etc.
  * @param seed Random seed for bootstrapping and choosing feature subsets.
  */
class RandomForestLearner (
                     override val ctx: TaskContext,
                     val strategy: Strategy,
                     val seed: Int)
  extends MLLearner(ctx)  {

  private val LOG = LogFactory.getLog(classOf[RandomForestLearner])

  def this(ctx: TaskContext, strategy: Strategy) = this(ctx, strategy, seed = 0)
  def this(ctx: TaskContext) = this(ctx, Strategy.initStrategy(ctx.getConf))

  private val totalNumTree = strategy.getNumTrees
  private val localTreeIdx = assignTrees

  private val model = RandomForestModel(conf, ctx)

  def convertDataBlock(input: DataBlock[LabeledData]): Array[LabeledData] = {
    input.resetReadIndex()
    val ret = new ArrayBuffer[LabeledData]
    (0 until input.size()).foreach{ _ =>
      ret += input.read()
    }
    ret.toArray
  }

  def assignTrees: Array[Int] =
    (0 until totalNumTree).filter(_%ctx.getTotalTaskNum == ctx.getTaskIndex).toArray

  /**
    * Method to train a decision tree model over an RDD
    *
    * @param trainBlock Training data: DataBlock of [[org.apache.spark.mllib.regression.LabeledPoint]].
    * @return RandomForestModel that can be used for prediction.
    */
  override
  def train(trainBlock: DataBlock[LabeledData], validBlock: DataBlock[LabeledData]): MLModel = {
    val trainDataSet: Array[LabeledData] = convertDataBlock(trainBlock)
    val validDataSet: Array[LabeledData] = convertDataBlock(validBlock)
    LOG.info(s"Task[${ctx.getTaskIndex}] builds trees ${localTreeIdx.mkString(",")}")
    LOG.info(s"Number of training: ${trainDataSet.length}, number of validation: ${validDataSet.length}")
    strategy.setNumTrees(localTreeIdx.length)
    val trees: Array[DecisionTreeModel] = RandomForest.train(ctx,
      trainDataSet, validDataSet, strategy, seed.toLong)
    new RandomForestModel(strategy.algo, trees, ctx.getConf, ctx)
  }
}

object RandomForestLearner {

  /**
    * Method to train a decision tree model for binary or multiclass classification.
    *
    * @param trainBlock Training dataset: DataBlock of [[LabeledData]].
    *              Labels should take values {0, 1, ..., numClasses-1}.
    * @param strategy Parameters for training each tree in the forest.
    * @param seed Random seed for bootstrapping and choosing feature subsets.
    * @return MLModel that can be used for prediction.
    */
  def trainClassifier(
                       ctx: TaskContext,
                       trainBlock: DataBlock[LabeledData],
                       validBlock: DataBlock[LabeledData],
                       strategy: Strategy,
                       seed: Int): MLModel = {
    require(strategy.algo == Classification,
      s"RandomForest.trainClassifier given Strategy with invalid algo: ${strategy.algo}")
    val rf = new RandomForestLearner(ctx, strategy, seed)
    rf.train(trainBlock, validBlock)
  }

  /**
    * Method to train a decision tree model for binary or multiclass classification.
    *
    * @param trainBlock Training dataset: DataBlock of [[LabeledData]].
    *              Labels should take values {0, 1, ..., numClasses-1}.
    * @param numClasses Number of classes for classification.
    * @param categoricalFeaturesInfo Map storing arity of categorical features. An entry (n to k)
    *                                indicates that feature n is categorical with k categories
    *                                indexed from 0: {0, 1, ..., k-1}.
    * @param numTrees Number of trees in the random forest.
    * @param featureSubsetStrategy Number of features to consider for splits at each node.
    *                              Supported values: "auto", "all", "sqrt", "log2", "onethird".
    *                              If "auto" is set, this parameter is set based on numTrees:
    *                                if numTrees == 1, set to "all";
    *                                if numTrees is greater than 1 (forest) set to "sqrt".
    * @param impurity Criterion used for information gain calculation.
    *                 Supported values: "gini" (recommended) or "entropy".
    * @param maxDepth Maximum depth of the tree (e.g. depth 0 means 1 leaf node, depth 1 means
    *                 1 internal node + 2 leaf nodes).
    *                 (suggested value: 4)
    * @param maxBins Maximum number of bins used for splitting features
    *                (suggested value: 100)
    * @param seed Random seed for bootstrapping and choosing feature subsets.
    * @return MLModel that can be used for prediction.
    */
  def trainClassifier(
                       ctx: TaskContext,
                       trainBlock: DataBlock[LabeledData],
                       validBlock: DataBlock[LabeledData],
                       impurity: String,
                       numTrees: Int,
                       maxDepth: Int,
                       numClasses: Int,
                       maxBins: Int,
                       subsamplingRate: Double,
                       featureSubsetStrategy: String,
                       categoricalFeaturesInfo: Map[Int, Int],
                       seed: Int = Random.nextInt()): MLModel = {
    val impurityType = Impurities.fromString(impurity)
    val strategy = new Strategy(Classification, impurityType, numTrees, maxDepth,
      numClasses, maxBins, subsamplingRate, featureSubsetStrategy, Sort, categoricalFeaturesInfo)
    trainClassifier(ctx, trainBlock, validBlock, strategy, seed)
  }

  /**
    * Method to train a decision tree model for regression.
    *
    * @param trainBlock Training dataset: DataBlock of [[LabeledData]].
    *              Labels are real numbers.
    * @param strategy Parameters for training each tree in the forest.
    * @param seed Random seed for bootstrapping and choosing feature subsets.
    * @return MLModel that can be used for prediction.
    */
  def trainRegressor(
                      ctx: TaskContext,
                      trainBlock: DataBlock[LabeledData],
                      validBlock: DataBlock[LabeledData],
                      strategy: Strategy,
                      seed: Int): MLModel = {
    require(strategy.algo == Regression,
      s"RandomForest.trainRegressor given Strategy with invalid algo: ${strategy.algo}")
    val rf = new RandomForestLearner(ctx, strategy, seed)
    rf.train(trainBlock, validBlock)
  }

  /**
    * Method to train a decision tree model for regression.
    *
    * @param trainBlock Training dataset: DataBlock of [[LabeledData]].
    *              Labels are real numbers.
    * @param categoricalFeaturesInfo Map storing arity of categorical features. An entry (n to k)
    *                                indicates that feature n is categorical with k categories
    *                                indexed from 0: {0, 1, ..., k-1}.
    * @param numTrees Number of trees in the random forest.
    * @param featureSubsetStrategy Number of features to consider for splits at each node.
    *                              Supported values: "auto", "all", "sqrt", "log2", "onethird".
    *                              If "auto" is set, this parameter is set based on numTrees:
    *                                if numTrees == 1, set to "all";
    *                                if numTrees is greater than 1 (forest) set to "onethird".
    * @param impurity Criterion used for information gain calculation.
    *                 The only supported value for regression is "variance".
    * @param maxDepth Maximum depth of the tree. (e.g., depth 0 means 1 leaf node, depth 1 means
    *                 1 internal node + 2 leaf nodes).
    *                 (suggested value: 4)
    * @param maxBins Maximum number of bins used for splitting features.
    *                (suggested value: 100)
    * @param seed Random seed for bootstrapping and choosing feature subsets.
    * @return MLModel that can be used for prediction.
    */
  def trainRegressor(
                      ctx: TaskContext,
                      trainBlock: DataBlock[LabeledData],
                      validBlock: DataBlock[LabeledData],
                      impurity: String,
                      numTrees: Int,
                      maxDepth: Int,
                      maxBins: Int,
                      subsamplingRate: Double = 1,
                      featureSubsetStrategy: String,
                      categoricalFeaturesInfo: Map[Int, Int],
                      seed: Int = Random.nextInt()): MLModel = {
    val impurityType = Impurities.fromString(impurity)
    val strategy = new Strategy(Regression, impurityType, numTrees, maxDepth,
      0, maxBins, subsamplingRate, featureSubsetStrategy, Sort, categoricalFeaturesInfo)
    trainRegressor(ctx, trainBlock, validBlock, strategy, seed)
  }
}


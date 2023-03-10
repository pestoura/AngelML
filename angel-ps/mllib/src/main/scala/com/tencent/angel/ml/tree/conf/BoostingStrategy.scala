/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */


package com.tencent.angel.ml.tree.conf

import com.tencent.angel.ml.tree.conf.Algo._
import com.tencent.angel.ml.tree.loss.{LogLoss, Loss, SquaredError}

/**
  * Configuration options for [[org.apache.spark.mllib.tree.GradientBoostedTrees]].
  *
  * @param treeStrategy Parameters for the tree algorithm. We support regression and binary
  *                     classification for boosting. Impurity setting will be ignored.
  * @param loss Loss function used for minimization during gradient boosting.
  * @param numIterations Number of iterations of boosting.  In other words, the number of
  *                      weak hypotheses used in the final model.
  * @param learningRate Learning rate for shrinking the contribution of each estimator. The
  *                     learning rate should be between in the interval (0, 1]
  * @param validationTol validationTol is a condition which decides iteration termination when
  *                      runWithValidation is used.
  *                      The end of iteration is decided based on below logic:
  *                      If the current loss on the validation set is greater than 0.01, the diff
  *                      of validation error is compared to relative tolerance which is
  *                      validationTol * (current loss on the validation set).
  *                      If the current loss on the validation set is less than or equal to 0.01,
  *                      the diff of validation error is compared to absolute tolerance which is
  *                      validationTol * 0.01.
  *                      Ignored when
  *                      `org.apache.spark.mllib.tree.GradientBoostedTrees.run()` is used.
  */
case class BoostingStrategy (
                              // Required boosting parameters
                              var treeStrategy: Strategy,
                              var loss: Loss,
                              // Optional boosting parameters
                              var numIterations: Int = 100,
                              var learningRate: Double = 0.1,
                              var validationTol: Double = 0.01) extends Serializable {

  /**
    * Check validity of parameters.
    * Throws exception if invalid.
    */
  private def assertValid(): Unit = {
    treeStrategy.algo match {
      case Classification =>
        require(treeStrategy.numClasses == 2,
          "Only binary classification is supported for boosting.")
      case Regression =>
      // nothing
      case _ =>
        throw new IllegalArgumentException(
          s"BoostingStrategy given invalid algo parameter: ${treeStrategy.algo}." +
            s"  Valid settings are: Classification, Regression.")
    }
    require(learningRate > 0 && learningRate <= 1,
      "Learning rate should be in range (0, 1]. Provided learning rate is " + s"$learningRate.")
  }
}

object BoostingStrategy {

  /**
    * Returns default configuration for the boosting algorithm
    * @param algo Learning goal.  Supported: "Classification" or "Regression"
    * @return Configuration for boosting algorithm
    */
  def defaultParams(algo: String): BoostingStrategy = {
    defaultParams(Algo.fromString(algo))
  }

  /**
    * Returns default configuration for the boosting algorithm
    * @param algo Learning goal.  Supported:
    *             `org.apache.spark.mllib.tree.configuration.Algo.Classification`,
    *             `org.apache.spark.mllib.tree.configuration.Algo.Regression`
    * @return Configuration for boosting algorithm
    */
  def defaultParams(algo: Algo): BoostingStrategy = {
    val treeStrategy = Strategy.defaultStrategy(algo)
    treeStrategy.maxDepth = 3
    algo match {
      case Algo.Classification =>
        treeStrategy.numClasses = 2
        new BoostingStrategy(treeStrategy, LogLoss)
      case Algo.Regression =>
        new BoostingStrategy(treeStrategy, SquaredError)
      case _ =>
        throw new IllegalArgumentException(s"$algo is not supported by boosting.")
    }
  }
}
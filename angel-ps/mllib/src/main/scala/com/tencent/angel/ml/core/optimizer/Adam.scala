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


package com.tencent.angel.ml.core.optimizer

import java.util.concurrent.Future

import com.tencent.angel.ml.core.utils.paramsutils.ParamKeys
import com.tencent.angel.ml.matrix.psf.update.base.VoidResult
import com.tencent.angel.ml.psf.optimizer.AdamUpdateFunc
import com.tencent.angel.psagent.PSAgentContext
import org.apache.commons.logging.LogFactory
import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import scala.collection.mutable


class Adam(stepSize: Double, var gamma: Double, var beta: Double) extends Optimizer(stepSize) {
  private val LOG = LogFactory.getLog(classOf[Adam])

  override protected var numSlot: Int = 3

  override def resetParam(paramMap: mutable.Map[String, Double]): Unit = {
    super.resetParam(paramMap)
    gamma = paramMap.getOrElse("gamma", gamma)
    beta = paramMap.getOrElse("beta", beta)
    epsilon = paramMap.getOrElse("epsilon", epsilon)
  }

  override def update(matrixId: Int, numFactors: Int, epoch: Int): Future[VoidResult] = {
    update(matrixId, numFactors, epoch, 1)
  }

  override def update(matrixId: Int, numFactors: Int, epoch: Int, batchSize: Int): Future[VoidResult] = {
    val func = new AdamUpdateFunc(matrixId, numFactors, gamma, epsilon, beta, lr, regL2Param, epoch, batchSize)
    PSAgentContext.get().getUserRequestAdapter.update(func)
  }

  override def toString: String = {
    s"Adam gamma=$gamma beta=$beta lr=$lr regL2=$regL2Param epsilon=$epsilon"
  }

  override def toJson: JObject = {
    (ParamKeys.typeName -> s"${this.getClass.getSimpleName}") ~
      (ParamKeys.beta-> beta) ~
      (ParamKeys.gamma -> gamma)
  }
}

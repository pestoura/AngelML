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

package com.tencent.angel.spark.ml

import com.tencent.angel.RunningMode
import com.tencent.angel.conf.AngelConf
import com.tencent.angel.ml.core.conf.{MLConf, SharedConf}
import com.tencent.angel.ml.matrix.RowType
import com.tencent.angel.spark.ml.classification.FactorizationMachine
import com.tencent.angel.spark.ml.core.{AutoOfflineLearner, OfflineLearner}

class AutoFMTest extends PSFunSuite with SharedPSContext {

  private var learner: AutoOfflineLearner = _
  private var input: String = _
  private var dim: Int = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    input = "../data/census/census_148d_train.libsvm"

    // build SharedConf with params
    SharedConf.get()
    SharedConf.get().set(MLConf.ML_MODEL_TYPE, RowType.T_FLOAT_DENSE.toString)
    SharedConf.get().setInt(MLConf.ML_FEATURE_INDEX_RANGE, 149)
    SharedConf.get().setDouble(MLConf.ML_LEARN_RATE, 0.5)
    SharedConf.get().set(MLConf.ML_DATA_INPUT_FORMAT, "libsvm")
    SharedConf.get().setInt(MLConf.ML_EPOCH_NUM, 10)
    SharedConf.get().setDouble(MLConf.ML_VALIDATE_RATIO, 0.1)
    SharedConf.get().setDouble(MLConf.ML_REG_L2, 0.0)
    SharedConf.get().setDouble(MLConf.ML_BATCH_SAMPLE_RATIO, 0.2)
    dim = SharedConf.indexRange.toInt

    SharedConf.get().set(AngelConf.ANGEL_RUNNING_MODE, RunningMode.ANGEL_PS.toString)

    SharedConf.get().setInt(MLConf.ML_AUTO_TUNER_ITER, 10)
    SharedConf.get().setBoolean(MLConf.ML_AUTO_TUNER_MINIMIZE, false)
    SharedConf.get().set(MLConf.ML_AUTO_TUNER_MODEL, "GaussianProcess")
    SharedConf.get().set(MLConf.ML_AUTO_TUNER_PARAMS, "ml.learn.rate|C|float|0.1:1:100")

    learner = new AutoOfflineLearner().init()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  test("FactorizationMachine") {
    val model = new FactorizationMachine
    learner.train(input, "", "", dim, model)
  }

}

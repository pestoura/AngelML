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


package com.tencent.angel.spark.automl.tuner.solver

import com.tencent.angel.spark.automl.tuner.TunerParam
import com.tencent.angel.spark.automl.tuner.acquisition.{Acquisition, EI}
import com.tencent.angel.spark.automl.tuner.acquisition.optimizer.{AcqOptimizer, RandomSearch}
import com.tencent.angel.spark.automl.tuner.config.{Configuration, ConfigurationSpace}
import com.tencent.angel.spark.automl.tuner.parameter.{ContinuousSpace, DiscreteSpace, ParamSpace}
import com.tencent.angel.spark.automl.tuner.surrogate._
import com.tencent.angel.spark.automl.utils.AutoMLException
import org.apache.spark.ml.linalg.{Vector,Vectors}
import org.apache.commons.logging.{Log, LogFactory}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class Solver(
              val cs: ConfigurationSpace,
              val surrogate: Surrogate,
              val acqFuc: Acquisition,
              val optimizer: AcqOptimizer,
              val surrogateMode: SurrogateMode.Value) {

  val LOG: Log = LogFactory.getLog(classOf[Solver])

  val PARAM_TYPES: Array[String] = Array("discrete", "continuous")

  var features:Array[Array[Double]] = Array[Array[Double]]()

  lazy val valid: Boolean = ensureValid()

  def ensureValid(): Boolean = {
    // ensure grid
    surrogateMode match {
      case SurrogateMode.GRID => cs.setAllToGrid()
      case _ =>
    }
    true
  }

  def getHistory(): (Array[Vector], Array[Double]) = (surrogate.preX.toArray, surrogate.preY.toArray)

  def getSurrogate: Surrogate = surrogate

  def addParam(param: ParamSpace[AnyVal]): Unit = {
    cs.addParam(param)
  }

  def addParam(pName: String, pType: String, vType: String, config: String, seed: Int): Unit = {
    pType.toLowerCase match {
      case "discrete" =>
        vType.toLowerCase match {
          case "float" => addParam(new DiscreteSpace[Float](pName, config))
          case "double" => addParam(new DiscreteSpace[Double](pName, config))
          case "int" => addParam(new DiscreteSpace[Int](pName, config))
          case "long" => addParam(new DiscreteSpace[Long](pName, config))
          case _ => throw new AutoMLException(s"unsupported value type $vType")
        }
      case "continuous" =>
        vType.toLowerCase match {
          case "double" => addParam(new ContinuousSpace(pName, config))
          case _ => throw new AutoMLException(s"unsupported value type $vType")
        }
      case _ => throw new AutoMLException(s"unsupported param type $pType, should be ${PARAM_TYPES.mkString(",")}")
    }
  }

  def getParamTypes: mutable.Map[String, (String, String)] = cs.paramType

  def getParamType(pName: String): (String, String) = {
    cs.getParamType(pName)
  }

  /**
    * Suggests configurations to evaluate.
    */
  def suggest(): Array[Configuration] = {
    surrogateMode match {
      case SurrogateMode.GP | SurrogateMode.RF =>
        val (acqAndConfig:Array[(Double, Configuration)],cateValues:Array[Array[Double]]) = optimizer.maximize(TunerParam.batchSize)
        features = cateValues
        print(s"suggest configurations: ")
        acqAndConfig.foreach { case (acq, config) =>
          print(s">> config[${config.getVector.toArray.mkString("(", ",", ")")}], " +
            s"acquisition[$acq] << ")
        }
        println()
        acqAndConfig.map(_._2)
      case SurrogateMode.RANDOM =>
        val (configs, cateValues) = cs.randomSample(TunerParam.batchSize)
        features = cateValues
        configs
      case SurrogateMode.GRID =>
        val (configs:Array[Configuration],cateValues:Array[Array[Double]]) = cs.gridSample(TunerParam.batchSize)
        features = cateValues
        configs
    }
  }

  /**
    * Feed evaluation result to the model
    *
    * @param configs : More evaluated configurations
    * @param Y       : More evaluation result
    */
  def feed(configs: Array[Configuration], Y: Array[Double]): Unit = {
    //println(s"feed ${configs.size} configurations")
    if (!configs.isEmpty && !Y.isEmpty) {
      val configArrays = configs.map(_.getVector)
      var oneHotConfigArray = new ArrayBuffer[Vector]()
      configArrays.foreach{ config =>
        val configArray = config.toArray
        var oneHotConfig = new ArrayBuffer[Double]()
        for ( (feature, config) <- (features zip configArray)){
          if (feature.contains(Double.MaxValue)){
            oneHotConfig +=config
          }
          else {
            feature.foreach{ value =>
              if(value == config){
                oneHotConfig += 1.0
              }
              else {
                oneHotConfig += 0.0
              }
            }
          }
        }
        oneHotConfigArray += Vectors.dense(oneHotConfig.toArray)
      }
      if (surrogate.minimize) {
        surrogate.update(oneHotConfigArray.toArray, Y.map(-_))
      }
      else {
        surrogate.update(oneHotConfigArray.toArray, Y)
      }
    }
    cs.addHistories(configs.map(_.getVector))
  }

  def feed(config: Configuration, y: Double): Unit = {
    val configArray = config.getVector.toArray
    var oneHotConfig = new ArrayBuffer[Double]()
    for ( (feature, config) <- (features zip configArray)){
      if (feature.contains(Double.MaxValue)){
        oneHotConfig +=config
      }
      else {
        feature.foreach{ value =>
          if(value == config){
            oneHotConfig += 1.0
          }
          else {
            oneHotConfig += 0.0
          }
        }
      }
    }
    if (surrogate.minimize)
      surrogate.update(Vectors.dense(oneHotConfig.toArray), -y)
    else
      surrogate.update(Vectors.dense(oneHotConfig.toArray), y)
  }

  def optimal(): (Vector, Double) = {
    var configArray:ArrayBuffer[Double] = new ArrayBuffer[Double]()
    for ((feature,config) <- features.flatMap(_.toList) zip surrogate.curBest._1.toArray) {
      if(feature==Double.MaxValue) {
        configArray +=  config
      }
      else if(config!=0.0) {
        configArray += feature
      }
    }
    val configVec = Vectors.dense(configArray.toArray)
    (configVec,surrogate.curBest._2)
  }

  def stop(): Unit = {
    surrogate.stop
  }
}

object Solver {

  def apply(cs: ConfigurationSpace, surrogate: Surrogate, acqFuc: Acquisition, optimizer: AcqOptimizer): Solver = {
    new Solver(cs, surrogate, acqFuc, optimizer, SurrogateMode.GP)
  }

  def apply(cs: ConfigurationSpace): Solver = {
    val sur: Surrogate = new GPSurrogate(cs, minimize = true)
    val acq: Acquisition = new EI(sur, 0.1f)
    val opt: AcqOptimizer = new RandomSearch(acq, cs)
    new Solver(cs, sur, acq, opt, SurrogateMode.GP)
  }

  def apply(cs: ConfigurationSpace, minimize: Boolean, surrogate: String): Solver = {
    val mode = SurrogateMode.fromString(surrogate)
    mode match {
      case SurrogateMode.GP =>
        val sur: Surrogate = new GPSurrogate(cs, minimize)
        val acq: Acquisition = new EI(sur, 0.1f)
        val opt: AcqOptimizer = new RandomSearch(acq, cs)
        new Solver(cs, sur, acq, opt, mode)
      case SurrogateMode.RF =>
        val sur: Surrogate = new RFSurrogate(cs, minimize)
        val acq: Acquisition = new EI(sur, 0.1f)
        val opt: AcqOptimizer = new RandomSearch(acq, cs)
        new Solver(cs, sur, acq, opt, mode)
      case SurrogateMode.RANDOM =>
        val sur = new NormalSurrogate(cs, minimize)
        val acq = new EI(sur, 0.1f)
        val opt = new RandomSearch(acq, cs)
        new Solver(cs, sur, acq, opt, mode)
      case SurrogateMode.GRID =>
        val sur = new NormalSurrogate(cs, minimize)
        val acq = new EI(sur, 0.1f)
        val opt = new RandomSearch(acq, cs)
        new Solver(cs, sur, acq, opt, mode)
    }
  }

  def apply[T <: AnyVal](array: Array[ParamSpace[T]], minimize: Boolean, surrogate: String): Solver = {
    val cs: ConfigurationSpace = new ConfigurationSpace("cs")
    array.foreach(cs.addParam)
    Solver(cs, minimize, surrogate)
  }

  def apply(minimize: Boolean): Solver = {
    val cs: ConfigurationSpace = new ConfigurationSpace("cs")
    val sur: Surrogate = new GPSurrogate(cs, minimize)
    val acq: Acquisition = new EI(sur, 0.1f)
    val opt: AcqOptimizer = new RandomSearch(acq, cs)
    new Solver(cs, sur, acq, opt, SurrogateMode.GP)
  }

}

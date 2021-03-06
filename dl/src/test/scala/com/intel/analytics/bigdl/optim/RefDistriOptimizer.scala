/*
 * Licensed to Intel Corporation under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * Intel Corporation licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.optim

import com.intel.analytics.bigdl.DataSet
import com.intel.analytics.bigdl.dataset.{MiniBatch}
import com.intel.analytics.bigdl.parameters.FP16CompressedTensor
import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.utils.{Table}
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

/**
 * The class is used as a reference optimizer in distribute optimizer unit test
 */
class RefDistriOptimizer[T: ClassTag](
  model: Module[T],
  dataset: DataSet[MiniBatch[T]],
  criterion: Criterion[T])(implicit ev: TensorNumeric[T])
  extends Optimizer[T, MiniBatch[T]](
    model, dataset, criterion
  ) {

  override def optimize(): Module[T] = {
    this.assertEngineInited()

    RefDistriOptimizer.optimize(
      model,
      dataset,
      criterion,
      optimMethod,
      state,
      endWhen,
      ev
    )
  }
}

object RefDistriOptimizer {
  def optimize[T: ClassTag](
    model: Module[T],
    dataset: DataSet[MiniBatch[T]],
    criterion: Criterion[T],
    optimMethod: OptimMethod[T],
    state: Table,
    endWhen: Trigger,
    ev: TensorNumeric[T]
  ): Module[T] = {
    val (w, g) = model.getParameters()
    var count = 0
    state("epoch") = state.get[Int]("epoch").getOrElse(1)
    state("neval") = state.get[Int]("neval").getOrElse(1)
    val partitionNum = dataset.toDistributed().data(train = true).partitions.length
    model.training()
    while (!endWhen(state)) {
      val data = dataset.toDistributed().data(train = true)
      val (lossSum, grad, batch) = data.mapPartitions(iter => {
        val (localW, localG) = model.getParameters()
        model.zeroGradParameters()
        val fp16W = new FP16CompressedTensor[T](localW)
        fp16W.deCompress(localW)
        val batch = iter.next()
        val input = batch.data
        val target = batch.labels
        val output = model.forward(input).asInstanceOf[Tensor[T]]
        val loss = criterion.forward(output, target)
        model.backward(input, criterion.backward(output, target))
        fp16W.compress(localG)
        fp16W.deCompress(localG)
        Iterator.single(loss, localG, input.size(1))
      }).reduce((l, r) => {
        (ev.plus(l._1, r._1), {
          l._2.add(r._2)
          val fp16W = new FP16CompressedTensor[T](l._2)
          fp16W.deCompress(l._2)
          l._2
        }, l._3 + r._3)
      })
      val loss = ev.divide(lossSum, ev.fromType(partitionNum))
      val gradients = grad.div(ev.fromType(partitionNum))
      optimMethod.optimize(_ => (loss, gradients), w, state)
      count += batch
      state("neval") = state[Int]("neval") + 1
      println(s"loss is $loss")
      if (count >= dataset.size()) {
        state("epoch") = state[Int]("epoch") + 1
        count = 0
      }
    }

    model
  }
}

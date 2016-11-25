/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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
package com.intel.analytics.bigdl.torch

import com.intel.analytics.bigdl.nn.Add
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.RandomGenerator._
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}


class AddSpec extends FlatSpec with BeforeAndAfter with Matchers{
  before {
    if (!TH.hasTorch()) {
      cancel("Torch is not installed")
    }
  }

  "A Add Module " should "generate correct output and grad" in {
    val inputN = 5
    val seed = 100
    RNG.setSeed(seed)
    val module = new Add[Double](inputN)
    val input = Tensor[Double](1, 5)
    input(Array(1, 1)) = 1
    input(Array(1, 2)) = 2
    input(Array(1, 3)) = 3
    input(Array(1, 4)) = 4
    input(Array(1, 5)) = 5

    val gradOutput = Tensor[Double](5)
    gradOutput(Array(1)) = 2
    gradOutput(Array(2)) = 5
    gradOutput(Array(3)) = 10
    gradOutput(Array(4)) = 17
    gradOutput(Array(5)) = 26

    val code = "torch.manualSeed(" + seed + ")\n" +
      "module = nn.Add(5)\n" +
      "module:reset()\n" +
      "bias = module.bias\n" +
      "output = module:forward(input)\n" +
      "gradInput = module:backward(input, gradOutput)\n" +
      "ones = module._ones\n"

    val (luaTime, torchResult) = TH.run(code, Map("input" -> input, "gradOutput" -> gradOutput),
      Array("output", "gradInput", "bias", "ones"))

    val luaOutput1 = torchResult("output").asInstanceOf[Tensor[Double]]
    val luaOutput2 = torchResult("gradInput").asInstanceOf[Tensor[Double]]
    val luaBias = torchResult("bias").asInstanceOf[Tensor[Double]]
    val luaOnes = torchResult("ones").asInstanceOf[Tensor[Double]]

    val start = System.nanoTime()
    module.reset()
    val bias = module.bias
    val output = module.forward(input)
    val gradInput = module.backward(input, gradOutput)
    val end = System.nanoTime()
    val scalaTime = end - start

    luaOutput1 should be(output)
    luaOutput2 should be(gradInput)
    luaBias should be(bias)

  }
}
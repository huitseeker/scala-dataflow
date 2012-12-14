package scala.dataflow.la

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.dataflow.array.FATestHelper

@RunWith(classOf[JUnitRunner])
class LASuite extends FunSuite with FATestHelper with ArrayLA with FlowArrayImpl {

  val n = 200

  test("create a matrix") {
    val m = ones(n, n)
    verEls(m.data)((x,i) => x == 1)
  }

  test("create a vector") {
    val v = ones(n)
    verEls(v.data)((x,i) => x == 1)
  }

  test("scalar product") {
    val v1 = ones(n)
    val v2 = ones(n)
    assert(block(v1 * v2) == n)
  }

  test("matrix vector product") {
    val m = ones(n,n)
    val v = ones(n)
    val res = m * v
    verEls(res.data)((x,i) => x == n)
  }

}

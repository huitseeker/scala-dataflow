package scala.dataflow

import scala.annotation.tailrec
import jsr166y._

class MultiLaneFlowPool[T](val lanes: Int) extends FlowPool[T] {

  import FlowPool._
  import MultiLaneFlowPool._
  
  val initBlocks = Array.fill(lanes)(newBlock(0))
  val sealHolder = new MLSealHolder()
  
  def newPool[S] = new MultiLaneFlowPool[S](lanes)

  def builder = new MultiLaneBuilder[T](initBlocks, sealHolder)

  def doForAll[U](f: T => U): Future[Int] = {
    val fut = new SumFuture[Int](lanes)

    // Note: Final sync of callback goes through SumFuture
    for (b <- initBlocks) {
      val cbe = new CallbackElem(f, fut.complete _, CallbackNil, b, 0)
      task(new RegisterCallbackTask(cbe))
    }

    fut
  }

  def mappedFold[U, V <: U](accInit: V)(cmb: (U,V) => V)(map: T => U): Future[(Int, V)] = {
    /* We do not need to synchronize on this var, because IN THE
     * CURRENT SETTING, callbacks are only executed in sequence ON EACH BLOCK
     * This WILL break if the scheduling changes
     */

    val fut = new SumFuture[Int](lanes)

    def accf(h: AccHolder[V])(x: T) =
      h.acc = cmb(map(x), h.acc)

    val holders = initBlocks map { b =>
      val h = new AccHolder(accInit)
      val cbe = new CallbackElem(accf(h) _, fut.complete _, CallbackNil, b, 0)
      task(new RegisterCallbackTask(cbe))
      h                            
    }

    fut map {
      c => (c, holders.map(_.acc).reduce(cmb))
    }

  }

}

object MultiLaneFlowPool {

  final class AccHolder[T](init: T) {
    @volatile var acc = init
  }

}
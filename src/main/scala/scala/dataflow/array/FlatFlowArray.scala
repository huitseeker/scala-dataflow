package scala.dataflow.array

import scala.annotation.tailrec

class FlatFlowArray[A : ClassManifest](
  private[array] val data: Array[A]
) extends ConcreteFlowArray[A] with Blocker {

  import FlowArray._

  // Fields
  val size = data.length

  final private[array] def dispatch(gen: JobGen, offset: Int) = {
    val job = gen(this, offset)
    dispatch(job)
    job
  }

  final private[array] def copyToArray(trg: Array[A], offset: Int) {
    Array.copy(data, 0, trg, offset, size)
  }

  override def jobDone() {
    setDone()
    freeBlocked()
  }

  override def blocking(isAbs: Boolean, msecs: Long): Array[A] = {
    block(false, msecs)
    data
  }

  final def unsafe(i: Int) = data(i)

}

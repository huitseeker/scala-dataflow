package scala.dataflow.array

import scala.dataflow.Future
import scala.annotation.tailrec

abstract class FlowArray[A : ClassManifest] extends Blocker with FAJob.Observer {

  import FlowArray._

  type JobGen = FAJob.JobGen[A]

  ///// Public API /////

  def size: Int
  def length = size

  def map[B : ClassManifest](f: A => B): FlowArray[B] = {
    val ret = newFA[B]
    setupDep(FAMapJob(ret, f), ret)
  }

  def zip[B : ClassManifest](that: FlowArray[B]) = zipMap(that)((_,_))

  def zipMap[B : ClassManifest, C : ClassManifest](
    that: FlowArray[B])(f: (A,B) => C): FlowArray[C] = {
      
    require(size == that.size)
    val ret = newFA[C]
    setupDep(FAZipMapJob(that, ret, f), ret)
  }

  def flatMapN[B : ClassManifest](n: Int)(f: A => FlowArray[B]): FlowArray[B] = {
    val ret = newFA[B](n)
    setupDep(FAFlatMapJob(ret, f, n), ret)
  }

  def mutConverge(cond: A => Boolean)(it: A => Unit): FlowArray[A] = {
    val ret = newFA[A]
    setupDep(FAMutConvJob(ret, it, cond), ret)
  }

  def converge(cond: A => Boolean)(it: A => A): FlowArray[A] = {
    val ret = newFA[A]
    setupDep(FAIMutConvJob(ret, it, cond), ret)
  }

  def fold[A1 >: A](z: A1)(op: (A1, A1) => A1): Future[A1] = {
    val ret = new FoldFuture(z, op)
    val job = dispatch(FAFoldJob(ret, z, op))
    job.addObserver(ret)
    ret
  }

  def slice(start: Int, end: Int): FlowArray[A] =
    new FlowArraySliceView(this, start, end - start + 1)

  /** partitions this FA into n chunks */
  def partition(n: Int): FlowArray[FlowArray[A]] = tabulate(n) { x =>
    slice(x * size / n, (x + 1) * size / n - 1)
  }

  def flatten[B](n: Int)(implicit flat: CanFlatten[A,B], mf: ClassManifest[B]): FlowArray[B]

  /** Checks if this job is done */
  def done: Boolean

  def blocking(isAbs: Boolean, msecs: Long): Array[A]

  def blocking: Array[A] = blocking(false, 0)

  ///// FlowArray package private API /////

  private[array] def unsafe(i: Int): A

  private[array] def copyToArray(dst: Array[A], srcPos: Int, dstPos: Int, length: Int): Unit

  // Slice-wise dependencies
  private[array] def sliceJobs(from: Int, to: Int): SliceDep

  // Dispatcher
  private[array] def dispatch(gen: JobGen): FAJob = dispatch(gen, 0, 0, size)
  private[array] def dispatch(gen: JobGen, dstOffset: Int, srcOffset: Int, length: Int): FAJob

  /** returns a job that aligns on this FlowArray with given offset and size */
  private[array] def align(offset: Int, size: Int): FAAlignJob[A]

  private[array] def tryAddObserver(obs: FAJob.Observer): Boolean

  private[array] final def addObserver(obs: FAJob.Observer) {
    if (!tryAddObserver(obs)) obs.jobDone()
  }

  ///// Private utility functions /////

  @inline private final def newFA[B : ClassManifest] = 
    new FlatFlowArray(new Array[B](length))

  @inline private final def newFA[B : ClassManifest](n: Int) = 
    new HierFlowArray(new Array[FlowArray[B]](size), n)

  @inline private final def setupDep[B](gen: JobGen, ret: ConcreteFlowArray[B]) = {
    val job = dispatch(gen)
    ret.generatedBy(job)
    ret
  }

}

object FlowArray {

  type SliceDep = Option[(IndexedSeq[FAJob], Boolean)]

  trait CanFlatten[A,B] {
    def flatten(fa: FlatFlowArray[A], n: Int): FlowArray[B]
  }

  implicit def flattenFaInFa[A : ClassManifest] = new CanFlatten[FlowArray[A], A] {
    def flatten(fa: FlatFlowArray[FlowArray[A]], n: Int) = {
      val res = new HierFlowArray(fa.data, n)
      res.generatedBy(fa)
      res
    }
  }

  def tabulate[A : ClassManifest](n: Int)(f: Int => A) = {
    val ret = new FlatFlowArray(new Array[A](n))
    val job = FAGenerateJob(ret, f)
    ret.generatedBy(job)
    FAJob.schedule(job)
    ret
  }

}

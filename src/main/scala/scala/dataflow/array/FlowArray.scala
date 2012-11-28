package scala.dataflow.array

import scala.dataflow.Future
import scala.annotation.tailrec

abstract class FlowArray[A : ClassManifest] extends FAJob.Observer {

  import FlowArray._

  type JobGen = (FlatFlowArray[A], Int) => FAJob

  // Fields
  def size: Int
  def length = size

  // Utilities
  @inline private final def newFA[B : ClassManifest] = 
    new FlatFlowArray(new Array[B](length))

  @inline private final def newFA[B : ClassManifest](n: Int) = 
    new HierFlowArray(new Array[FlowArray[B]](size), n)

  private[array] def copyToArray(trg: Array[A], offset: Int): Unit

  // Slice-wise dependencies
  private[array] def sliceJobs(from: Int, to: Int): SliceDep

  // Dispatcher
  private[array] def dispatch(gen: JobGen): FAJob = dispatch(gen, 0)
  private[array] def dispatch(gen: JobGen, offset: Int): FAJob

  @inline private final def setupDep[B](gen: JobGen, ret: ConcreteFlowArray[B]) = {
    val job = dispatch(gen)
    ret.generatedBy(job)
    ret
  }

  // Public members
  def map[B : ClassManifest](f: A => B): FlowArray[B] = {
    val ret = newFA[B]
    setupDep((fa, of) => FAMapJob(fa, ret, f, of), ret)
  }

  def zip[B : ClassManifest](that: FlowArray[B]) = zipMap(that)((_,_))

  def zipMap[B : ClassManifest, C : ClassManifest](
    that: FlowArray[B])(f: (A,B) => C): FlowArray[C] = {
      
    require(size == that.size)
    val ret = newFA[C]
    setupDep((fa, of) => FAZipMapJob(fa, that, ret, f, of), ret)
  }

  def flatMapN[B : ClassManifest](n: Int)(f: A => FlowArray[B]): FlowArray[B] = {
    val ret = newFA[B](n)
    setupDep((fa, of) => FAFlatMapJob(fa, ret, f, n, of), ret)
  }

  def mutConverge(cond: A => Boolean)(it: A => Unit): FlowArray[A] = {
    val ret = newFA[A]
    setupDep((fa, of) => FAMutConvJob(fa, ret, it, cond, of), ret)
  }

  def converge(cond: A => Boolean)(it: A => A): FlowArray[A] = {
    val ret = newFA[A]
    setupDep((fa, of) => FAIMutConvJob(fa, ret, it, cond, of), ret)
  }

  def fold[A1 >: A](z: A1)(op: (A1, A1) => A1): Future[A1] = {
    val ret = new FoldFuture(z, op)
    val job = dispatch((fa, of) => FAFoldJob(fa, ret, z, op))
    job.addObserver(ret)
    ret
  }

  private[array] def tryAddObserver(obs: FAJob.Observer): Boolean
  private[array] def addObserver(obs: FAJob.Observer): Unit

  /** Checks if this job is done */
  def done: Boolean

  def unsafe(i: Int): A
  def blocking(isAbs: Boolean, msecs: Long): Array[A]
  def blocking: Array[A] = blocking(false, 0)

}

object FlowArray {

  type SliceDep = Option[(IndexedSeq[FAJob], Boolean)]

  def tabulate[A : ClassManifest](n: Int)(f: Int => A) = {
    val ret = new FlatFlowArray(new Array[A](n))
    val job = FAGenerateJob(ret, f)
    ret.generatedBy(job)
    FAJob.schedule(job)
    ret
  }

}

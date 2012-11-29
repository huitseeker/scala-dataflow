package scala.dataflow.array

private[array] class FAFoldJob[A : ClassManifest, A1 >: A] private (
  val src: FlatFlowArray[A],
  val trg: FoldFuture[A1],
  val z: A1,
  val f: (A1, A1) => A1,
  start: Int,
  end: Int,
  thr: Int,
  obs: FAJob.Observer
) extends FAJob(start, end, thr, obs) {

  protected def subCopy(s: Int, e: Int) = 
    new FAFoldJob(src, trg, z, f, s, e, thresh, this)

  protected def doCompute() {
    var tmp = z
    for (i <- start to end) {
      tmp = f(tmp, src.data(i))
    }
    trg.acc(tmp)
  }

  protected override def covers(from: Int, to: Int) = false

}

object FAFoldJob {

  import FAJob.JobGen

  def apply[A : ClassManifest, A1 >: A](
    trg: FoldFuture[A1],
    z: A1,
    f: (A1, A1) => A1
  ) = new JobGen[A] {
    def apply(src: FlatFlowArray[A], dstOffset: Int, srcOffset: Int, length: Int) =
      new FAFoldJob(src, trg, z, f, srcOffset,
                    srcOffset + length - 1, FAJob.threshold(length), null)
  }

}

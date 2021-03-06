package scala.dataflow

import java.lang.Thread
import java.util.concurrent.ThreadLocalRandom
import scala.collection.mutable.{ Set => MSet }
import impl._

object MLFPTest extends App {
  import Utils._

  val tasks = 1000
  val n = 100
  val pool = new MultiLaneFlowPool[(Int,Int)](tasks)
  val b = pool.builder

  val vals = MSet.empty[(Int,Int)]

  val sfill = pool.foreach { x =>
    vals.synchronized {
      vals += x
    }
  }
  val rf = pool.mapFold(0)(_ + _)(x => x._2)
  val sf = pool.mapFold(0)(_ + _)(x => x._1)

  val rc = n * (n + 1) / 2 * tasks
  val sc = tasks * (tasks + 1) / 2 * n

  task {
    val v = ThreadLocalRandom.current()
    Thread.sleep(v.nextLong(1000))
    b.seal(n * tasks)
  }

  for (i <- 1 to tasks) {
    task {
      val v = ThreadLocalRandom.current()
      for (j <- 1 to n) {
        b << (i,j)
        Thread.sleep(v.nextLong(10))
      }
    }
  }

  val rv = rf.blocking
  val sv = sf.blocking
  val c = sfill.blocking

  val vals_should = ( for (i <- 1 to tasks ; j <- 1 to n) yield (i,j) ) toSet

  val missing = vals_should.diff(vals).size

  println("Count: Reported: %d, should: %d".format(c,n * tasks))
  println("%d values missing (diff)".format(missing))
  println("%d values missing (sub)".format(vals_should.size - vals.size))
  println("Done: r: %d (should: %d, %s), s: %d (should: %d, %s)".format(
    rv,rc,if(rv==rc) "eq" else "ne",sv,sc, if(sv==sc) "eq" else "ne"))

}

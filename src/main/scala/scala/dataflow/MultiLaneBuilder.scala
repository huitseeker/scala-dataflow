package scala.dataflow

import scala.annotation.tailrec
import jsr166y._

final class MultiLaneBuilder[T](
  bls: Array[Array[AnyRef]],
  sealHolder: MLSealHolder
) extends Builder[T] {

  import MLSealHolder._

  @volatile private var positions = bls.map(bl => Next(bl))
  
  private val unsafe = getUnsafe()
  private val ARRAYOFFSET      = unsafe.arrayBaseOffset(classOf[Array[AnyRef]])
  private val ARRAYSTEP        = unsafe.arrayIndexScale(classOf[Array[AnyRef]])
  @inline private def RAWPOS(idx: Int) = ARRAYOFFSET + idx * ARRAYSTEP
  @inline private def CAS(bl: Array[AnyRef], idx: Int, ov: AnyRef, nv: AnyRef) =
    unsafe.compareAndSwapObject(bl, RAWPOS(idx), ov, nv)
  private val SH_OFFSET = unsafe.objectFieldOffset(
    classOf[MLSealHolder].getDeclaredField("s"))
  @inline private def CAS_SH(ov: State, nv: State) = 
    unsafe.compareAndSwapObject(this, SH_OFFSET, ov, nv)
  
  @tailrec
  def <<(x: T): this.type = {
    val bli = getblocki
    val position = positions(bli)

    val p = /*READ*/position
    val curblock = p.block
    val pos = /*READ*/p.index
    val npos = pos + 1
    val next = curblock(npos)
    val curo = curblock(pos)

    if (curo.isInstanceOf[CallbackList[_]] && ((next eq null) || next.isInstanceOf[CallbackList[_]])) {
      if (CAS(curblock, npos, next, curo)) {
        if (CAS(curblock, pos, curo, x.asInstanceOf[AnyRef])) {
          p.index = npos
          applyCallbacks(curo.asInstanceOf[CallbackList[T]])
          this
        } else <<(x)
      } else <<(x)
    } else {
      if (tryAdd(x,bli)) this
      else <<(x)
    }
  }
  
  @tailrec
  def seal(size: Int) {
    // TODO like this, seal is not linearizable
    
    val st = /*READ*/sealHolder.s
    st match {
      case Unsealed => {
        val ns = Proposition(size)
        if (!CAS_SH(st, ns)) seal(size)
        else {
          helpSeal(ns)
          // We succeeded. Our job now to clean up SealTags
          // TODO
        }
      }
      case p: Proposition => helpSeal(p)
      case MLSeal(sz) if size != sz =>
        sys.error("already sealed at %d (!= %d)".format(sz, size))
    }

  }

  private def helpSeal(p: Proposition) {
    var sizes: Int = 0
    
    for ((pos,bli) <- positions zipWithIndex) {
      sealTag(p, bli, /*READ*/pos.block, /*READ*/pos.index) match {
        case Some(v) => sizes = sizes + v
        case None => return
      }
    }

    
    if (sizes <= p.size) {
      // At this point we know that the seal MUST succeed
      val nv = new MLSeal(p.size, p.size - sizes)
      CAS_SH(p, nv)
    } else {
      // At this point we know that the seal MUST fail
      CAS_SH(p, Unsealed)
      sys.error("Seal size too small (total size %d>%d)".format(
        sizes, p.size))
    }

  }
  
  @tailrec
  private def sealTag(
      p: Proposition,
      bli: Int,
      curblock: Array[AnyRef],
      pos: Int
    ): Option[Int] = {

    import FlowPool._

    curblock(pos) match {
      case MustExpand =>
        expand(curblock, bli)
        sealTag(p, bli, curblock, pos)
      case Next(block) =>
        sealTag(p, bli, block, 0)
      case cbl: CallbackList[_] =>
        if (pos < LAST_CALLBACK_POS) {
          val cnt = totalElems(curblock, pos)
          val nv = SealTag(p, cbl)
          if (cnt > p.size)
            sys.error("Seal size too small " +
                      "(found %d>%d in a block)".format(cnt,p.size))
          else if (CAS(curblock, pos, cbl, nv)) Some(cnt)
          else sealTag(p, bli, curblock, pos)
        } else sealTag(p, bli, curblock, pos + 1)
      case Seal(sz, _) =>
        /*READ*/sealHolder.s match {
          case MLSeal(ssz) =>
            if (sz == ssz) None
            else sys.error("Seal failed (by other thread)")
          case _ =>
            sys.error("MultiLaneFlowPool in inconsistent" +
                      "state. (Seal found without global seal)")
        }
      case ov @ SealTag(op, cbl) =>
        val cnt = totalElems(curblock, pos)
        if (op eq p) Some(cnt)
        else {
          /*READ*/sealHolder.s match {
            case gpr: Proposition =>
              if (gpr ne p) sys.error("Seal failed (by other thread)")
              else {
                val nv = SealTag(p, cbl)
                if (cnt > p.size)
                  sys.error("Seal size too small (found %d>" +
                            "%d in a block)".format(cnt,p.size))
                else if (CAS(curblock, pos, ov, nv))
                  Some(cnt)
                else
                  sealTag(p, bli, curblock, pos)
              }
            case MLSeal(sz) => 
              val nv = ov.toSeal(cnt)
              if (CAS(curblock, pos, ov, nv)) None
              else sealTag(p, bli, curblock, pos)
            case Unsealed =>
              sys.error("Seal failed (by other thread)")
          }
        }
      case _ =>
        sealTag(p, bli, curblock, pos + 1)
    }
  }
  
  private def totalElems(curblock: Array[AnyRef], pos: Int) = {
    import FlowPool._
    val blockidx = curblock(IDX_POS).asInstanceOf[Int]
    blockidx * MAX_BLOCK_ELEMS + pos
  }
  
  private def goToNext(next: Next, bli: Int) {
    positions(bli) = next // ok - not racey
  }
  
  private def expand(curblock: Array[AnyRef], bli: Int) {
    import FlowPool._

    val at = MUST_EXPAND_POS
    val curidx = curblock(IDX_POS).asInstanceOf[Int]

    // Prepare new block with CBs
    val nextblock = newBlock(curidx + 1,curblock(at - 1))
    
    val next = Next(nextblock)
    if (CAS(curblock, at, MustExpand, next)) {
      // take a shortcut here
      goToNext(next, bli)
    }
  }
  
  
  private def tryAdd(x: T, bli: Int): Boolean = {
    import FlowPool._

    val position = positions(bli)
    
    val p = /*READ*/position
    val curblock = p.block
    val pos = /*READ*/p.index
    val obj = curblock(pos)

    obj match {
      case Seal(sz, null) => // flowpool sealed here - error
        sys.error("Insert on a sealed structure.")
      case MustExpand => // must extend with a new block
        expand(curblock, bli)
      case ne @ Next(_) => // the next block already exists - go to it
        goToNext(ne, bli)
      case cbh: CallbackHolder[_] => // a list of callbacks here - check if this is the end of the block
        val nextelem = curblock(pos + 1)
        nextelem match {
          case MustExpand =>
            expand(curblock, bli)
          case ne @ Next(_) =>
            goToNext(ne, bli)
          case _: CallbackHolder[_] | null =>
            // current is Seal(sz, _ != null), next is not at the end
            // check size and append
            val curelem = curblock(pos)
            curelem match {
              case Seal(sz, cbs) =>
                val total = totalElems(curblock, pos)
                val nseal = if (total < (sz - 1)) curelem else Seal(sz, null)
                if (CAS(curblock, pos + 1, nextelem, nseal)) {
                  if (CAS(curblock, pos, curelem, null)) {
                    p.index = pos + 1
                    applyCallbacks(cbh.callbacks)
                    return true
                  }
                }
              case _ =>
            }
          case _ =>
        }
      case _ => // a regular object - advance
        throw new Exception
        p.index = pos + 1
        tryAdd(x, bli)
    }
    false
  }
  
  @tailrec
  private def applyCallbacks[T](e: CallbackList[T]): Unit = e match {
    case el: CallbackElem[T] =>
      el.awakeCallback()
      applyCallbacks(el.next)
    case _ =>
  }

  /**
   * gets index of block index to use, based on current thread
   */
  private def getblocki = {
    (Thread.currentThread.getId % positions.length).asInstanceOf[Int]
  }
  
}

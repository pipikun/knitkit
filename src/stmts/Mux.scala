package knitkit

import ir._
import internal._

object Mux {
  def pushMux(dest: Bits, cond: Expression, tval: Expression, fval: Expression): Bits = {
    dest.bind(OpBinding(Builder.forcedUserModule))
    dest.setRef(ir.Mux(cond, tval, fval))
    dest
  }

  def check(cond: Bits, con: Data, alt: Data): Unit = {
    cond.width match {
      case IntWidth(x) =>
        require(x == 1, s"Conditional bit width must be 1, not $x")
      case UnknownWidth =>
        Builder.error(s"Cond width must be 1")
    }
    requireIsHardware(cond, "mux condition")
    requireIsHardware(con , "mux true value")
    requireIsHardware(alt , "mux false value")
  }

  def apply(cond: Bits, con: Bits, alt: Bits): Bits = {
    check(cond, con, alt)
    val elt = if (cond.width == (cond.width max alt.width)) con else alt
    val dest = elt.cloneType
    pushMux(dest, cond.ref, con.ref, alt.ref)
  }

  def apply(cond: Bits, con: Vec, alt: Vec): Vec = {
    check(cond, con, alt)
    Vec((con.elements zip alt.elements) map { case (c, a) =>
      apply(cond, c, a)
    })
  }

  def apply(cond: Bits, con: Data, alt: Data): Data = {
    check(cond, con, alt)
    (con, alt) match {
	   case (c: Bits, a: Bits) =>
       apply(cond, c, a)
	   case (c: Vec, a: Vec) =>
       apply(cond, c, a)
     case _ =>
       Builder.error(s"Mux's params type Bits only")
   }
  }
}

object MuxCase {
  def apply(default: Bits, mapping: Seq[(Bits, Bits)]): Bits = {
    var res = default
    for ((t, v) <- mapping.reverse){
      res = Mux(t, v, res)
    }
    res
  }
}

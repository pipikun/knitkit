package knitkit

import ir._
import internal._

object Mux {
  def pushMux[T <: Bits](dest: T, cond: Expression, tval: Expression, fval: Expression): T = {
    dest.bind(OpBinding(Builder.forcedUserModule))
    dest.setRef(ir.Mux(cond, tval, fval))
    dest
  }

  def check(cond: Data, con: Data, alt: Data): Unit = {
    check(cond.asBits, con, alt)
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

  def apply[T <: Bits](cond: Bits, con: T, alt: T): T = {
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

  def apply(cond: Bits, con: Aggregate, alt: Aggregate): Aggregate = {
    check(cond, con, alt)
    Agg(con.eles map { case (name, ele) =>
      name -> apply(cond, ele, alt(name))
    })
  }

  def apply(cond: Data, con: Data, alt: Data): Data = {
    apply(cond.asBits, con, alt)
  }

  def apply(cond: Bits, con: Data, alt: Data): Data = {
    check(cond, con, alt)
    (con, alt) match {
	   case (c: Bits, a: Bits) =>
       apply(cond, c, a)
	   case (c: Vec, a: Vec) =>
       apply(cond, c, a)
	   case (c: Aggregate, a: Aggregate) =>
       apply(cond, c, a)
     case _ =>
       Builder.error(s"Mux's Must both are Bits, Vec, Or Agg")
   }
  }
}

object MuxCase {
  def apply(default: Data, mapping: Seq[(Bits, Data)]): Data = {
    var res = default
    for ((t, v) <- mapping.reverse){
      res = Mux(t, v, res)
    }
    res
  }

  def apply(default: Vec, mapping: Seq[(Bits, Vec)]): Vec = {
    var res = default
    for ((t, v) <- mapping.reverse){
      res = Mux(t, v, res)
    }
    res
  }

  def apply(default: Aggregate, mapping: Seq[(Bits, Aggregate)]): Aggregate = {
    var res = default
    for ((t, v) <- mapping.reverse){
      res = Mux(t, v, res)
    }
    res
  }

  def apply[T <: Bits](default: T, mapping: Seq[(Bits, T)]): T = {
    var res = default
    for ((t, v) <- mapping.reverse){
      res = Mux(t, v, res)
    }
    res
  }
}

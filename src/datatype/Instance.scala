package knitkit

import collection.mutable.HashMap

import internal._
import ir._
import Utils._

case class Instance(port_map: Seq[(String, Data)]) extends HasId {
  def clone_fn(clone: Data, orig: Data): Data = {
    val new_clone = clone_fn_all(clone, orig)
    new_clone.setRef(InstanceIO(this, orig.computeName(None, "INST_IO")))
    new_clone
  }

  val ports = port_map map { case (n, d) => d match {
    case v: Vec =>
      n -> {
        val vec = v.clone(clone_fn _)
        vec
      }
	  case a: Aggregate =>
      n -> {
        val agg = a.clone(clone_fn _)
        agg
      }
	  case b: Bits =>
      n -> b.clone(clone_fn _)
    }
  }

  def apply(port: String): Data = {
    val p_map = ports.toMap
    val p = p_map(port)
    p.bypass = false
    p.used = true
    p
  }
}

package knitkit

import internal._
import internal.Builder.error
import Utils._

class AliasedAggregateFieldException(message: String) extends knitkitException(message)

class Aggregate(val eles: Seq[(String, Data)]) extends Data with AggOps {
  def elements: Map[String, Data] = eles.toMap

  val duplicates = getElements.groupBy(identity).collect { case (x, elts) if elts.size > 1 => x }
  if (!duplicates.isEmpty) {
    throw new AliasedAggregateFieldException(s"Aggregate $this contains aliased fields $duplicates")
  }

  def getDir: SpecifiedDirection = error(s"Vec Not Support get direction")
  def apply(idx: Int*): Data = error(s"Aggregate Not Support idx extract")

  def apply(name: String) = {
    val e = elements(name)
    e.used = true
    e
  }

  def _onModuleClose: Unit = {
    for ((name, elt) <- eles) {
      elt._parentID = Some(this)
      //elt.setRef(this, elt.computeName(None, name))
    }
  }

  def alter(fn: Data => Unit): Aggregate = {
    eles foreach { case (_, data)  => fn(data) }
    this
  }

  def prefix(str_list: Seq[String]): Aggregate = {
    Agg(str_list flatMap { s =>
      eles map { case (name, elt) =>
        val clone_elt = (elt match {
          case v: Vec => v.clone(clone_fn_base _)
          case a: Aggregate => a.clone(clone_fn_base _)
          case b: Bits => b.clone(clone_fn_base _)
        })
        if (clone_elt.suggested_name.isEmpty) {
          clone_elt.suggestName(s"${s}_$name")
        } else {
          clone_elt.suggestName(s"${s}_${clone_elt.suggested_name.get}")
        }
        s"${s}_$name" -> clone_elt
      }
    })
  }

  def suffix(str_list: Seq[String]): Aggregate = {
    Agg(str_list flatMap { s =>
      eles map { case (name, elt) =>
        val clone_elt = (elt match {
          case v: Vec => v.clone(clone_fn_base _)
          case a: Aggregate => a.clone(clone_fn_base _)
          case b: Bits => b.clone(clone_fn_base _)
        })
        if (clone_elt.suggested_name.isEmpty) {
          clone_elt.suggestName(s"${name}_$s")
        } else {
          clone_elt.suggestName(s"${clone_elt.suggested_name.get}_${s}")
        }

        s"${name}_$s" -> clone_elt
      }
    })
  }

  def prefix(s: String): this.type = {
    for ((_, elt) <- eles) { elt.prefix(s) }
    this
  }

  def suffix(s: String): this.type = {
    for ((_, elt) <- eles) { elt.suffix(s) }
    this
  }

  def flip: this.type = {
    for ((_, elt) <- eles) { elt.flip }
    this
  }

  def getElements: Seq[Data] = eles.map(_._2)

  def flattenElements: Seq[Bits] = {
    getElements flatMap {
      case a: Aggregate => a.flattenElements
      case v: Vec       => v.flattenElements
      case b: Bits      => Seq(b)
    }
  }

  def reversedVecElements: Seq[Bits] = {
    getElements flatMap {
      case a: Aggregate => a.reversedVecElements
      case v: Vec       => v.reversedVecElements
      case b: Bits      => Seq(b)
    }
  }

  def getPair: Seq[(String, Data)] = eles

  def bind(target: Binding): Unit = {
    binding = target
  }

  def clone(fn: (Data, Data) => Data = (x, y) => x): Aggregate = {
    val agg = new Aggregate((eles map { case (name, data) =>
      val clone_data = data match {
        case b: Bits      =>
          b.clone(fn)
        case a: Aggregate => a.clone(fn)
        case v: Vec       => v.clone(fn)
      }
      name -> clone_data
    }).toSeq)
    fn(agg, this) match {
      case a: Aggregate => a
      case _ => Builder.error("Agg clone should be Aggregate")
    }
  }

  def asUInt: Bits = {
    Cat(reversedVecElements map { _.asUInt })
  }

  def asUIntGroup(group_num: Int = 0, prefix: String = "CAT"): Bits = {
    val eles = reversedVecElements map { _.asUInt }
    CatGroup(eles, group_num, prefix)
  }
}

object Agg {
  def bind(agg: Aggregate): Aggregate = {
    // check elements' binding must be the same
    (agg.getElements zip agg.getElements.drop(1)) foreach { case (a, b) =>
      if (!sameBinding(a, b)) {
        error(s"Vec elements' binding must be the same $a $b")
      }
    }
    agg._binding = agg.getElements(0)._binding
    agg
  }

  def apply(a: (String, Data), r: (String, Data)*): Aggregate = apply(a :: r.toList)
  def apply(your_eles: Seq[(String, Data)]): Aggregate = {
    val named_eles = your_eles map { case (n, d) => n -> d.suggestName(n, alter = false) }
    bind(new Aggregate(named_eles))
  }
}


trait AggOps { this: Aggregate =>

  def not_def_op = Builder.error(s"Not define Ops, use Bits!")

  def +  (that: Data): Bits = not_def_op
  def +& (that: Data): Bits = not_def_op
  def -  (that: Data): Bits = not_def_op
  def &  (that: Data): Bits = not_def_op
  def |  (that: Data): Bits = not_def_op
  def ^  (that: Data): Bits = not_def_op
  def *  (that: Data): Bits = not_def_op
  def /  (that: Data): Bits = not_def_op
  def %  (that: Data): Bits = not_def_op

  def <   (that: Data): Bits = not_def_op
  def >   (that: Data): Bits = not_def_op
  def <=  (that: Data): Bits = not_def_op
  def >=  (that: Data): Bits = not_def_op
  def =/= (that: Data): Bits = not_def_op
  def === (that: Data): Bits = not_def_op

  def || (that: Data): Bits = not_def_op
  def && (that: Data): Bits = not_def_op

  def << (that: Int ): Bits = not_def_op
  def << (that: Data): Bits = not_def_op

  def >> (that: Int ): Bits = not_def_op
  def >> (that: Data): Bits = not_def_op

  def asBool  = not_def_op
  def asClock = not_def_op
  def asClockNeg = not_def_op
  def asReset = not_def_op
  def asAsyncPosReset = not_def_op
  def asAsyncNegReset = not_def_op
}

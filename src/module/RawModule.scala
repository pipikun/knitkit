package knitkit

import collection.mutable.{HashMap, ArrayBuffer, Set}

import internal._
import internal.Builder._
import ir._
import VerilogRender._
import Utils._

abstract class RawModule extends BaseModule with HasConditional {
  // Module Local Variables
  val _inst_stmts = ArrayBuffer[Statement]()
  val _wire_eles = Set[Bits]()
  val _wire_as_reg_eles = Set[Bits]()
  val _inWhenOrSwitch = Set[Bits]()

  val _clks_info = HashMap[ClkInfo, ArrayBuffer[Bits]]()
  val _regs_info = HashMap[Bits, RegInfo]()

  val port_wires = HashMap[Long, Bits]()

  def copyRegInfo(src: Bits, dest: Bits): Unit = {
    require(_regs_info.contains(src), "Copy RegInfo must contains source")
    val src_reg_info = _regs_info(src)
    val src_clk_info = src_reg_info.clk_info

    require(_clks_info.contains(src_clk_info), "Copy ClkInfo must contains source")
    _clks_info(src_clk_info) += dest

    if (_regs_info.contains(dest)) {
      //Builder.error("dest already in RegInfo")
    } else {
      _regs_info(dest) = src_reg_info
    }
  }

  def pushRegInfo(reg: Bits, clk: ClkInfo, reg_info: RegInfo): Unit = {
    if (!_clks_info.contains(clk)) {
      _clks_info(clk) = ArrayBuffer[Bits](reg)
    } else {
      _clks_info(clk) += reg
    }
    if (_regs_info.contains(reg)) {
      Builder.error("Define register twice")
    } else {
      _regs_info(reg) = reg_info
    }
  }

  val _wire_connects = HashMap[Bits, Bits]()
  val _reg_connects  = HashMap[Bits, Bits]()
  def pushConn(lhs: Bits, rhs: Bits, is_reg: Boolean = false): Unit = {
    if (is_reg) {
      _reg_connects += (lhs -> rhs)
    } else {
      _wire_connects += (lhs -> rhs)
    }
  }

  def addWire(e: Bits): Unit =  {
    require(!_closed, "Can't write to module after module close")
    e.binding match {
      case WireBinding(_) =>
        _wire_eles += e
      case _ =>
        error(s"$e is not Wire!")
    }
  }
  def addWireAsReg(e: Bits): Unit =  {
    require(!_closed, "Can't write to module after module close")
    e.binding match {
      case WireBinding(_) =>
        _wire_as_reg_eles += e
      case _ =>
        error(s"$e is not Wire!")
    }
  }

  def pushInst[T <: Instance](inst: T): Unit = {
    require(_closed, "Can't push instance before module close")
    val cur_module = Builder.forcedUserModule
    cur_module._inst_stmts += DefInstance(inst, name, Map())
  }

  def wrap_when_init(e: Bits, stmts: Seq[Statement] = Seq()): Seq[Statement] = {
    _regs_info(e) match {
      case RegInfo(ClkInfo(_, Some(rst_val)), Some(init_val)) =>
        if (stmts.nonEmpty) {
          Seq(WhenBegin(rst_val, true), Connect(e.lref, init_val), WhenEnd()) ++
            Seq(OtherwiseBegin()) ++ stmts ++ Seq(OtherwiseEnd())
        } else {
          Seq(WhenBegin(rst_val, true), Connect(e.lref, init_val), WhenEnd())
        }
      case RegInfo(_, _) =>
        stmts
    }
  }

  def isArr(b: Bits): Boolean = {
    b._ref match {
      case Some(NodeArray(_, _, _)) => true
      case _ => false
    }
  }
  def getStatements: Seq[Seq[Statement]] = {
    require(_closed, "Can't get commands before module close")
    require(_wire_as_reg_eles.subsetOf(_wire_eles), s"${_wire_as_reg_eles} not in ${_wire_eles}" )
    val wire_decl        = _wire_eles.diff(_wire_as_reg_eles).toSeq.sortBy(_._id) filter { !isArr(_) } map { x =>  DefWire(x.ref) }
    val wire_as_reg_decl = _wire_as_reg_eles.toSeq.sortBy(_._id) filter { !isArr(_) } map { x => DefWire(x.ref, true) }
    val reg_decl         = sortedIDs(_regs_info) filter { case (e, _) => !isArr(e) } map { case (e, _) => DefWire(e.ref, true) }

    val wire_assigns = sortedIDs(_wire_connects filter { case (l, _) => !_inWhenOrSwitch.contains(l)}) map { case(l, r) => Assign(l.lref, r.ref) }

    val always_blocks = sortedIDs(_reg_connects filter { case (l, _) => !_inWhenOrSwitch.contains(l)}) map { case (lhs, rhs) =>
      Always(_regs_info(lhs).clk_info, wrap_when_init(lhs, Seq(Connect(lhs.lref, rhs.ref))))
    }

    val decl_stmts = wire_decl ++ wire_as_reg_decl ++ reg_decl

    if (decl_stmts.nonEmpty) {
      val max_decl_width = (decl_stmts map { x => x.decl_width }).max
      val has_signed = decl_stmts map { _.is_signed_tpe } reduce { _ || _ }

      if (has_signed) {
        decl_stmts foreach { x => x.set_pad_signed }
      }

      decl_stmts foreach { x => x.decl_width = max_decl_width }
    }

    // println(_reg_connects)
    Seq(decl_stmts, wire_assigns, _inst_stmts.toSeq, always_blocks)
  }

  def autoConnectPassIO(): Unit = {
    require(!_closed, "Can't auto connect IO when module closed!")
    _inst_stmts foreach { case DefInstance(inst, _, _) =>
      inst.ports foreach { case (name, p) =>
        if (p.bypass) {
          cloneIO(p, name)
        }
      }
    }
  }

  def resolve_inst_output_port(p: Bits): Unit = {
    if (p._conn.size > 1) {
      val wire = Wire(p.cloneType)

      wire.setRef(p.ref)

      p._conn foreach { _ := wire }

    } else {
      if (isBothInChildModule(p, p._conn.last)) {
        val pair_ref = (p._conn.last.getRef, p.getRef) match {
          case (l: InstanceIO, r: InstanceIO) => PairInstIO(l, r, false)
          case (l, r) => Builder.error(s"Should be use instance port! ${l} ${r}")
        }

        p.setRef(pair_ref)
        val wire = Wire(p.cloneType)
        wire.setRef(pair_ref)
      } else {
        p.setRef(p._conn.last.ref)
      }
    }
  }

  def resolve_inst_input_port(p: Bits): Unit = {
    // if (p._conn.size > 1) println(s"Multi connected to Input port: ${p.computeName()} of ${desiredName}, only last one connected")
    p.setRef(p._conn.last.ref)
  }

  def resolve_inst_inout_port(p: Bits): Unit = {
    require(p._conn.size == 1, "Analog must connect one target")
    (p._ref, p._conn.last._ref) match {
      case (Some(l: InstanceIO), Some(r: InstanceIO)) =>
        val pair_ref = PairInstIO(l, r, false)
        p.setRef(pair_ref)
        val wire = Wire(p.cloneType)
        wire.setRef(pair_ref)
      case (Some(l: InstanceIO), _) =>
        p.setRef(p._conn.last.ref)
      case _ =>
        Builder.error(s"Not support yet")
    }
  }

  def decl_inst_port_output(d: Data):Unit = d match {
    case v: Vec =>
      v.getElements map { decl_inst_port_output(_) }
    case a: Aggregate =>
      a.getElements map { decl_inst_port_output(_) }
    case b: Bits =>
      if (b._conn.isEmpty) {
        // if (b.used) {
        val e = b.cloneType
        e.bind(WireBinding(this))
        e.setRef(b.getRef)
        _wire_eles += e
        b.setConn(e)
        // }
      } else {
        b.direction match {
          case SpecifiedDirection.InOut =>
          case SpecifiedDirection.Input =>
          case SpecifiedDirection.Output =>
            resolve_inst_output_port(b)
          case other => error(s"Port direction error: ${other}")
        }
      }
  }

  // Must after decl_inst_port_output
  def decl_inst_port_other(d: Data):Unit = d match {
    case v: Vec =>
      v.getElements map { decl_inst_port_other(_) }
    case a: Aggregate =>
      a.getElements map { decl_inst_port_other(_) }
    case b: Bits =>
      if (b._conn.isEmpty) {
        // if (b.used) {
        val e = b.cloneType
        e.bind(WireBinding(this))
        e.setRef(b.getRef)
        _wire_eles += e
        b.setConn(e)
        // }
      } else {
        b.direction match {
          case SpecifiedDirection.InOut =>
            resolve_inst_inout_port(b)
          case SpecifiedDirection.Input =>
            resolve_inst_input_port(b)
          case SpecifiedDirection.Output =>
          case other => error(s"Port direction error: ${other}")
        }
      }
  }

  def prepareComponent(): Unit = {
    for (id <- _ids) {
      id match {
        case inst: Instance =>
          inst.ports foreach { case (_, p) => decl_inst_port_output(p) }
        case _ =>
      }
    }
    for (id <- _ids) {
      id match {
        case inst: Instance =>
          inst.ports foreach { case (_, p) => decl_inst_port_other(p) }
        case _ =>
      }
    }

  }

  def generateComponent(): Component = {
    require(!_closed, "Can't generate module more than once")
    _closed = true

    val names = nameIds(classOf[RawModule])

    namePorts(names)

    for ((node, name) <- names) {
      if (node.decl_name == "") {
        node.decl_name = name
      }
      node match {
        case n: Data =>
          n.bindingOpt match {
            case Some(PortBinding(inst)) =>
              if (inst == this)
                node.suggestName(name, alter = false)
            case _ =>
              node.suggestName(name, alter = false)
          }
        case _ =>
          node.suggestName(name, alter = false)
      }
    }

    // All suggestions are in, force names to every node.
    val (bits_id, other_id) = getIds partition {
      x => x match {
        case _: Bits => true
        case _ => false
      }
    }
    for (id <- other_id) {
     id match {
       case inst: Instance =>
         inst.forceName(None, default="INST", _inst_namespace)
       case agg: Aggregate =>
         agg.forceName(None, default="AGG", _namespace, rename = false)
         agg._onModuleClose
       case vec: Vec =>
         vec.forceName(None, default="VEC", _namespace, rename = false)
         vec._onModuleClose
       case DontCare =>
         // Ignore DontCare
       case other => error(s"Unknow ID: $other")
     }
    }
    for (id <- bits_id) {
      id match {
        case id: Bits =>
         if (id.isSynthesizable) {
           id.bindingOpt match {
             case Some(PortBinding(_)) =>
               id.forceName(None, default="PORT", _namespace, rename = false)
             case Some(RegBinding(_)) =>
               id.forceName(None, default="REG", _namespace)
             case Some(WireBinding(_)) =>
               id.forceName(None, default="WIRE", _namespace)
             case Some(_) => // don't name literals
               id.forceName(Some(""), default="T", _namespace)
             case _ =>  // don't name literals
           }
         } // else, don't name unbound types
        case other => error(s"Unknow ID: $other")
      }
    }

    val modulePorts = getModulePorts flatMap { p => genPortIR(p, _port_as_reg.contains(p)) }

    def toConn(info: Map[Bits, Bits]): Map[Expression, Expression] = {
      info map { case (lhs, rhs) => lhs.lref -> rhs.ref }
    }

    def add_when_default(connects: HashMap[Bits, Bits], ele: Bits): Seq[Statement] = {
      if (connects.contains(ele)) {
        Seq(OtherwiseBegin(),
            Connect(ele.lref, connects(ele).ref),
            OtherwiseEnd(),
        )
      } else {
        Seq()
      }
    }

    val switch_scope_regs  = ArrayBuffer[Statement]()
    val switch_scope_wires = ArrayBuffer[Statement]()

    def sorted_cases(cases: Seq[(SwitchCondition, ArrayBuffer[Statement])] ): Seq[(SwitchCondition, Seq[Statement])] = {
      val (default, other) = (cases map { case (cond, stmts) => cond -> stmts.toSeq }).sortBy(_._1.idx).partition(_._1.idx == -1)
      other ++ default
    }

    val switch_stmts = sortedIDs(switchScope) flatMap { case (id, scope) =>
      scope.toSeq.sortBy { case (info, _) => info.idx } map { case (clk_info, cases) =>
        val info = clk_info.info
        // Check Defalut
        switch_defalut(id)(info) foreach { x =>
          if (_reg_connects.contains(x)) {
            error(s"${str_of_expr(x.ref)} has two default value, outside switch case default is: ${str_of_expr(_reg_connects(x).ref)} ")
          }
          if (_wire_connects.contains(x)) {
            error(s"${str_of_expr(x.ref)} has two default value, outside switch case default is: ${str_of_expr(_wire_connects(x).ref)} ")
          }

        }

        val switch_body = info match {
          case ClkInfo(Some(_), Some(rst_val)) =>
            // Check Defalut
            val no_default_reg  = switchRegs(id)(info)  filter { x => !switch_defalut(id)(info).contains(x) }
            val default_reg  = _reg_connects  filter { case (x, _) => no_default_reg.contains(x)  }
            if (cases.contains(SwitchCondition(-1, None, None))) {
              cases(SwitchCondition(-1, None, None)) ++= (default_reg map { case (l, r) => Connect(l.lref, r.ref) })
            } else {
              cases(SwitchCondition(-1, None, None)) = ArrayBuffer((default_reg map { case (l, r) => Connect(l.lref, r.ref) }).toSeq: _*)
            }
            val reg_inits = switchRegs(id)(info) map { bit => bit -> _regs_info(bit).init }
            val reg_conns = (reg_inits map { case (bit, init) => init match {
              case Some(init_val) => Some(Connect(bit.lref, init_val))
              case None => None
            }}).flatten
            val init_when = Seq(WhenBegin(rst_val, true)) ++ reg_conns ++ Seq(WhenEnd())
            val else_when = Seq(OtherwiseBegin(), SwitchScope(id.lref, sorted_cases(cases.toSeq)), OtherwiseEnd())
            init_when ++ else_when
          case ClkInfo(Some(_), None) =>
            // Check Defalut
            val no_default_reg  = switchRegs(id)(info)  filter { x => !switch_defalut(id)(info).contains(x) }
            val default_reg  = _reg_connects  filter { case (x, _) => no_default_reg.contains(x)  }
            if (cases.contains(SwitchCondition(-1, None, None))) {
              cases(SwitchCondition(-1, None, None)) ++= (default_reg map { case (l, r) => Connect(l.lref, r.ref) })
            } else if (default_reg.nonEmpty){
              cases(SwitchCondition(-1, None, None)) = ArrayBuffer((default_reg map { case (l, r) => Connect(l.lref, r.ref) }).toSeq: _*)
            }

            Seq(SwitchScope(id.lref, sorted_cases(cases.toSeq)))
          case _ =>
            // Check Defalut
            val no_default_wire = switchWires(id)(info) filter { x => !switch_defalut(id)(info).contains(x) }
            val default_wire = _wire_connects filter { case (x, _) => no_default_wire.contains(x) }
            if (cases.contains(SwitchCondition(-1, None, None))) {
              cases(SwitchCondition(-1, None, None)) ++= (default_wire map { case (l, r) => Connect(l.lref, r.ref) })
            } else if (default_wire.nonEmpty){
              cases(SwitchCondition(-1, None, None)) = ArrayBuffer((default_wire map { case (l, r) => Connect(l.lref, r.ref) }).toSeq: _*)
            }

            Seq(SwitchScope(id.lref, sorted_cases(cases.toSeq)))
        }
        Always(info, switch_body)
      }
    }

    val whenScopeStatements = {
      val scope = whenScope map { case (ele, stmts) =>
        val init_stmt = if (_regs_info.contains(ele)) wrap_when_init(ele) else Seq()
        val wrap_stmts =
          if (init_stmt.nonEmpty) {
            val first = stmts(0) match {
	            case WhenBegin(pred, _) => Seq(WhenBegin(pred, false))
              case other => error(s"First of When statements must be WhenBegin: $other")
            }
            init_stmt ++ first ++ stmts.tail
          } else {
            stmts
          }
        if (_wire_connects.contains(ele) || _reg_connects.contains(ele)) {
          stmts.last match {
            case OtherwiseEnd() => error(s"${str_of_expr(ele.ref)} in $desiredName only one default!")
            case _ =>
          }
        }
        ele -> (wrap_stmts ++ add_when_default(_wire_connects, ele) ++ add_when_default(_reg_connects, ele))
      }
      scope.toSeq.sortWith { case ((a, _), (b, _)) => a._id < b._id } map { case (e, stmts) =>
        val info = if (_regs_info.contains(e)) _regs_info(e).clk_info else ClkInfo(None, None)
        Always(info, stmts.toSeq)
      }
    }

    DefModule(name, modulePorts, getStatements ++ Seq(whenScopeStatements, switch_stmts))
  }
}

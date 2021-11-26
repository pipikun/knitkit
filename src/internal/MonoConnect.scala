package knitkit.internal

import collection.mutable.{Set, ArrayBuffer}

import knitkit._
import knitkit.ir._
import knitkit.Utils._
import Builder._

object MonoConnect {
  def UnwritableSinkException =
    MonoConnectException(": Sink is unwriteable by current module.")
  def UnknownRelationException =
    MonoConnectException(": Sink or source unavailable to current module.")
  def MismatchedException(sink: String, source: String) =
    MonoConnectException(s": Sink ($sink) and Source ($source) have different types.")

  def connect(
    sink: Data,
    source: Data,
    context_mod: RawModule,
    concise: Boolean,
  ): Unit =
    (sink, source) match {
      case (sink_e: Bits, DontCare) =>
        val dontcare_source = sink_e.tpe match {
	        case AsyncNegResetType => 1.B
          case _ => 0.B
        }
        elemConnect(sink_e, dontcare_source, context_mod, concise)
      case (sink_e: Bits, source_e: Bits) =>
        require(sameType(sink_e, source_e), s"${sink_e.tpe} and ${source_e.tpe} 's type not the same")
        elemConnect(sink_e, source_e, context_mod, concise)
      case (sink, source) => throw MismatchedException(sink.toString, source.toString)
    }

  def elemConnect(sink: Bits, source: Bits, context_mod: RawModule, concise: Boolean): Unit = {
    import SpecifiedDirection.{Internal, Input, Output, InOut}
    val sink_mod: BaseModule   = sink.binding.location.getOrElse(throw UnwritableSinkException)
    val source_mod: BaseModule = source.binding.location.getOrElse(context_mod)

    val sink_direction   = sink.direction
    val source_direction = source.direction

    // CASE 1: Context is same module that both left node and right node are in
    if( (context_mod == sink_mod) && (context_mod == source_mod) ) {
      ((sink_direction, source_direction)) match {
        //    SINK          SOURCE
        //    CURRENT MOD   CURRENT MOD
        case (Output  , _) => issueConnect(sink, source)
        case (Internal, _) => issueConnect(sink, source)
        case (_       , _) => throw UnwritableSinkException
      }
    }
    // CASE 2: Context is same module as sink node and right node is in a child module
    else if( (sink_mod == context_mod) && (source_mod != context_mod) ) {
      source.getRef match {
        case InstanceIO(_, _) =>
        case _ => Builder.error("Should be use instance port!")
      }
      ((sink_direction, source_direction)) match {
        //    SINK        SOURCE
        //    CURRENT MOD CHILD MOD
        case (Internal,   Output)   => source.setConn(sink.ref)
          // val wire = Wire(source.cloneType)
          // wire.setRef(source.getRef)
          // source.setConn(source.getRef)
          // issueConnect(sink, wire)
        case (Output,     Output) => source.setConn(sink.ref)
        case (InOut,      InOut ) => source.setConn(sink.ref)
        case (_,          _     ) => throw UnwritableSinkException
      }
    }
    // CASE 3: Context is same module as source node and sink node is in child module
    else if( (source_mod == context_mod) && (sink_mod != context_mod) ) {
      sink.getRef match {
        case InstanceIO(_, _) =>
        case _ => Builder.error("Should be use instance port!")
      }
      sink.setConn(source.ref)
      ((sink_direction, source_direction)) match {
        //    SINK          SOURCE
        //    CHILD MOD     CURRENT MOD
        case (Input,  Internal) =>
        case (Input,  Input   ) =>
        case (InOut,  InOut   ) =>
        case (_    ,  _) => throw UnwritableSinkException
      }
    }
    // CASE 4: Context is the parent module of both the module containing sink node
    //                                        and the module containing source node
    //   Note: This includes case when sink and source in same module but in parent
    else {
      val pair_ref = (sink.getRef, source.getRef) match {
        case (l: InstanceIO, r: InstanceIO) => PairInstIO(l, r, concise)
        case (_, _) => Builder.error(s"Should be use instance port! ${sink.getRef} ${source.getRef}")
      }
      val wire = Wire(source.cloneType)
      wire.setRef(pair_ref)

      sink.setRef(pair_ref)
      sink.setConn(pair_ref)

      source.setRef(pair_ref)
      source.setConn(pair_ref)
      ((sink_direction, source_direction)) match {
        //    SINK      SOURCE
        //    CHILD MOD CHILD MOD
        case (Input, Output) =>
        case (InOut, InOut ) =>
        case (_, _) => throw UnwritableSinkException
      }
    }
  }

  private def issueConnect(sink: Bits, source: Bits): Unit = {
    val cur_module = Builder.forcedUserModule
    if (cur_module.whenScopeBegin) {
      require(!cur_module.currentInWhenScope.contains(sink), s"Can't connect $sink twice")
      cur_module._inWhenOrSwitch += sink
      cur_module.currentInWhenScope += sink
      cur_module.currentWhenStmt foreach { stmt =>
        Builder.forcedUserModule.pushWhenScope(sink, stmt)
      }
      sink.binding match {
        case PortBinding(_) =>
          cur_module._port_as_reg += sink
        case WireBinding(_) =>
          cur_module.addWireAsReg(sink)
        case _ =>
      }
      Builder.forcedUserModule.pushWhenScope(sink, (Connect(sink.lref, source.ref)))
    } else if (cur_module.switch_id.nonEmpty) {
      cur_module._inWhenOrSwitch += sink
      sink.binding match {
        case RegBinding(_) =>
          // Add Defaults
          val reg_info = cur_module._regs_info(sink)
          cur_module.switch_case match {
            case Some(SwitchCondition(_, None, None)) =>
              val switch_default = cur_module.switch_defalut(cur_module.switch_id.get)
              if (switch_default.contains(reg_info.clk_info)) {
                switch_default(reg_info.clk_info) += sink
              } else {
                switch_default(reg_info.clk_info) = ArrayBuffer(sink)
              }
            case _ =>
          }

          val switch_regs = cur_module.switchRegs(cur_module.switch_id.get)
          if (switch_regs.contains(reg_info.clk_info)) {
           switch_regs(reg_info.clk_info) += sink
          } else {
           switch_regs(reg_info.clk_info) = Set(sink)
          }

          cur_module.pushSwitchScope(reg_info.clk_info, Connect(sink.lref, source.ref))
        //case other@Other WireBinding(_) =>
        case other =>
          // Add Defaults
          val reg_info = RegInfo(ClkInfo(None, None), None)
          cur_module.switch_case match {
            case Some(SwitchCondition(_, None, None)) =>
              val switch_default = cur_module.switch_defalut(cur_module.switch_id.get)
              if (switch_default.contains(reg_info.clk_info)) {
                switch_default(reg_info.clk_info) += sink
              } else {
                switch_default(reg_info.clk_info) = ArrayBuffer(sink)
              }
            case _ =>
          }

          val switch_wires = cur_module.switchWires(cur_module.switch_id.get)
          if (switch_wires.contains(reg_info.clk_info)) {
           switch_wires(reg_info.clk_info) += sink
          } else {
           switch_wires(reg_info.clk_info) = Set(sink)
          }

          cur_module.pushSwitchScope(ClkInfo(None, None), Connect(sink.lref, source.ref))

          other match {
            case PortBinding(_) => cur_module._port_as_reg += sink
            case WireBinding(_) => cur_module.addWireAsReg(sink)
            case _ =>
          }
      }
    } else if (cur_module.switch_id.isEmpty && !cur_module.whenScopeBegin){
      sink.binding match {
        case RegBinding(_) =>
          Builder.forcedUserModule.pushConn(sink, source, true)
        case _ =>
          Builder.forcedUserModule.pushConn(sink, source)
      }
    }
  }

}

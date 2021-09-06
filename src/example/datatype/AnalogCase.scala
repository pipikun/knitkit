package example

import knitkit._

class IOPad extends ExtModule {
  val I   = IO(Input(Bool()))
  val OEN = IO(Input(Bool()))
  val O   = IO(Output(Bool()))
  val Pad = IO(InOut(Bool()))
}

class AnalogCase extends RawModule {
  val I   = IO(Input(Bool()))
  val OEN = IO(Input(Bool()))
  val O   = IO(Output(Bool()))
  val Pad = IO(InOut(Bool()))

  val pad = Module(new IOPad())()

  pad("I"  ) <> I
  pad("OEN") <> OEN
  pad("O"  ) <> O
  pad("Pad") <> Pad
}

package freechips.rocketchip.taic

import Chisel._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.interrupts._


object TAICConsts {
  def base: BigInt = 0x1000000
  def size = 0x1000000
  
}

case class TAICParams(baseAddress: BigInt = TAICConsts.base, intStages: Int = 0) {
  def address = AddressSet(baseAddress, TAICConsts.size - 1)
}

case object TAICKey extends Field[Option[TAICParams]](None)

case class TAICAttachParams(slaveWhere: TLBusWrapperLocation = CBUS)

case object TAICAttachKey extends Field(TAICAttachParams())

/** Asynchorous-Task-Scheduler-Interrupt Controller */
class TAIC(params: TAICParams, beatBytes: Int)(implicit p: Parameters) extends LazyModule {

  val device = new SimpleDevice("taic", Seq("riscv,taic")) {
    override val alwaysExtended: Boolean = true

    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      val extra = Map("interrupt-controller" -> Nil, "#interrupt-cells" -> Seq(ResourceInt(1)))
      Description(name, mapping ++ extra)
    }
  }

  val node: TLRegisterNode = TLRegisterNode(
    address = Seq(params.address),
    device = device,
    beatBytes = beatBytes,
    concurrency = 1) // limiting concurrency handles RAW hazards on claim registers

  val intnode: IntNexusNode = IntNexusNode(
    sourceFn = { _ => IntSourcePortParameters(Seq(IntSourceParameters(1, Seq(Resource(device, "int"))))) },
    sinkFn   = { _ => IntSinkPortParameters(Seq(IntSinkParameters())) },
    outputRequiresInput = false,
    inputRequiresOutput = false)

    def nDevices: Int = intnode.edges.in.map(_.source.num).sum

  lazy val module = new LazyModuleImp(this) {
    Annotated.params(this, params)

    // Compact the interrupt vector the same way
    // val interrupts = intnode.in.map { case (i, e) => i.take(e.source.num) }.flatten
    
    println(s"TAIC map ${nDevices} external interrupts:")


    // val deqReg = Seq(0x00 -> Seq(RegField.r(ATSINTCConsts.dataWidth, queue.io.deq)))
    // val enqRegs = Seq.tabulate(ATSINTCConsts.numPrio) { i =>
    //   0x08 + 8 * i -> Seq(RegField.w(ATSINTCConsts.dataWidth, queue.io.enqs(i)))
    // }
    // val simExtIntrRegs = Seq.tabulate(nDevices) { i => 
    //   0x200000 + 8 * i -> Seq(RegField.w(ATSINTCConsts.dataWidth, RegWriteFn { (valid, data) =>
    //     val tmp = RegNext(valid)
    //     queue.io.intrs(i) := valid || tmp
    //     true.B
    //   }))
    // }
    // val extintrRegs = Seq.tabulate(nDevices) { i =>
    //   0x900 + 8 * i -> Seq(RegField.w(ATSINTCConsts.dataWidth, queue.io.intrh_enqs(i)))
    // }

    // node.regmap((deqReg ++ enqRegs ++ extintrRegs ++ simExtIntrRegs): _*)
    
  }
}

/** Trait that will connect a TAIC to a subsystem */
trait CanHavePeripheryTAIC {
  this: BaseSubsystem =>
  val taicOpt = p(TAICKey).map { params =>
    val tlbus = locateTLBusWrapper(p(TAICAttachKey).slaveWhere)
    val taic = LazyModule(new TAIC(params, cbus.beatBytes))
    taic.node := tlbus.coupleTo("taic") { TLFragmenter(tlbus) := _ }
    taic.intnode :=* ibus.toPLIC

    InModuleBody {
      taic.module.clock := tlbus.module.clock
      taic.module.reset := tlbus.module.reset
    }

    taic
  }
}
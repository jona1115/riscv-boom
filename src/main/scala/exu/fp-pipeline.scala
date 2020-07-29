//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Floating Point Datapath Pipeline
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.rocket
import freechips.rocketchip.tile

import boom.common._
import boom.util.{BoomCoreStringPrefix}

/**
 * Top level datapath that wraps the floating point issue window, regfile, and arithmetic units.
 */
class FpPipeline(implicit p: Parameters) extends BoomModule with tile.HasFPUParameters
{
  val fpIssueParams = issueParams.find(_.iqType == IQT_FP.litValue).get
  val dispatchWidth = fpIssueParams.dispatchWidth
  val numLlPorts = lsuWidth
  val numWakeupPorts = fpIssueParams.issueWidth + numLlPorts
  val fpPregSz = log2Ceil(numFpPhysRegs)

  val io = IO(new Bundle {
    val brupdate         = Input(new BrUpdateInfo())
    val flush_pipeline   = Input(Bool())
    val fcsr_rm          = Input(UInt(width=freechips.rocketchip.tile.FPConstants.RM_SZ.W))
    val status           = Input(new freechips.rocketchip.rocket.MStatus())

    val dis_uops         = Vec(dispatchWidth, Flipped(Decoupled(new MicroOp)))

    // +1 for recoding.
    val ll_wports        = Flipped(Vec(lsuWidth, Valid(new ExeUnitResp(fLen+1))))// from memory unit
    val from_int         = Flipped(Decoupled(new ExeUnitResp(fLen+1)))// from integer RF
    val dgen             = Valid(new MemGen)           // to Load/Store Unit
    val to_int           = Decoupled(new ExeUnitResp(xLen))           // to integer RF

    val wakeups          = Vec(numWakeupPorts, Valid(new ExeUnitResp(fLen+1)))
    val wb_valids        = Input(Vec(numWakeupPorts, Bool()))
    val wb_pdsts         = Input(Vec(numWakeupPorts, UInt(width=fpPregSz.W)))

    val debug_tsc_reg    = Input(UInt(width=xLen.W))
  })

  //**********************************
  // construct all of the modules

  val exe_units: Seq[FPExeUnit] = (0 until fpWidth) map { w =>
    Module(new FPExeUnit(
      hasFDiv = usingFDivSqrt && (w==fpWidth-1),
      hasFpiu = (w==fpWidth-1)))
  }
  val numFrfReadPorts = fpWidth * 3
  val numFrfWritePorts = fpWidth + lsuWidth

  val issue_unit     = Module(new IssueUnitCollapsing(
                         issueParams.find(_.iqType == IQT_FP.litValue).get,
                         numWakeupPorts))
  issue_unit.suggestName("fp_issue_unit")
  val fregfile       = Module(new FullyPortedRF(
    UInt((fLen+1).W),
    numFpPhysRegs,
    numFrfReadPorts,
    numFrfWritePorts,
    "Floating Point"
  ))

  //*************************************************************
  // Issue window logic

  val iss_uops   = issue_unit.io.iss_uops

  issue_unit.io.tsc_reg := io.debug_tsc_reg
  issue_unit.io.brupdate := io.brupdate
  issue_unit.io.flush_pipeline := io.flush_pipeline
  // Don't support ld-hit speculation to FP window.
  for (w <- 0 until lsuWidth) {
    issue_unit.io.spec_ld_wakeup(w).valid := false.B
    issue_unit.io.spec_ld_wakeup(w).bits := 0.U
  }
  issue_unit.io.ld_miss := false.B
  issue_unit.io.squash_grant := false.B


  //-------------------------------------------------------------
  // **** Dispatch Stage ****
  //-------------------------------------------------------------

  // Input (Dispatch)
  for (w <- 0 until dispatchWidth) {
    issue_unit.io.dis_uops(w) <> io.dis_uops(w)
  }

  //-------------------------------------------------------------
  // **** Issue Stage ****
  //-------------------------------------------------------------

  // Output (Issue)
  for (i <- 0 until issue_unit.issueWidth) {

    var fu_types = exe_units(i).io_ready_fu_types
    issue_unit.io.fu_types(i) := fu_types

  }

  // Wakeup
  for ((writeback, issue_wakeup) <- io.wakeups zip issue_unit.io.wakeup_ports) {
    issue_wakeup.valid := writeback.valid
    issue_wakeup.bits  := writeback.bits
  }
  issue_unit.io.pred_wakeup_port.valid := false.B
  issue_unit.io.pred_wakeup_port.bits := DontCare


  issue_unit.io.iss_uops zip exe_units map { case (i, u) => u.io_iss_uop := i }

  //-------------------------------------------------------------
  // **** Register Arbitrate Stage ****
  //-------------------------------------------------------------

  var rd_idx = 0
  for (unit <- exe_units) {
    for (i <- 0 until 3) {
      fregfile.io.arb_read_reqs(rd_idx) <> unit.io_arb_frf_reqs(i)
      rd_idx += 1
    }
  }
  require(rd_idx == numFrfReadPorts)

  //-------------------------------------------------------------
  // **** Register Read Stage ****
  //-------------------------------------------------------------

  rd_idx = 0
  for (unit <- exe_units) {
    for (i <- 0 until 3) {
      unit.io_rrd_frf_resps(i) := fregfile.io.rrd_read_resps(rd_idx)
      rd_idx += 1
    }
  }
  require(rd_idx == numFrfReadPorts)


  //-------------------------------------------------------------
  // **** Execute Stage ****
  //-------------------------------------------------------------

  exe_units.map(_.io_brupdate := io.brupdate)



  //-------------------------------------------------------------
  // **** Writeback Stage ****
  //-------------------------------------------------------------

  val ll_wbarb = Module(new Arbiter(new ExeUnitResp(fLen+1), 1 + // mem
                                                             1 + // fromint
                                                             1   // fdiv
  ))


  // Hookup load writeback -- and recode FP values.
  ll_wbarb.io.in(0).valid := RegNext(io.ll_wports(0).valid)
  ll_wbarb.io.in(0).bits  := RegNext(io.ll_wports(0).bits)
  ll_wbarb.io.in(0).bits.data := recode(RegNext(io.ll_wports(0).bits.data),
                                        RegNext(io.ll_wports(0).bits.uop.mem_size =/= 2.U))

  val ifpu_resp = io.from_int
  ll_wbarb.io.in(1) <> ifpu_resp

  ll_wbarb.io.in(2) <> exe_units.find(_.hasFDiv).get.io_fdiv_resp.get


  // Cut up critical path by delaying the write by a cycle.
  // Wakeup signal is sent on cycle S0, write is now delayed until end of S1,
  // but Issue happens on S1 and RegRead doesn't happen until S2 so we're safe.
  fregfile.io.write_ports(0).valid := ll_wbarb.io.out.valid && ll_wbarb.io.out.bits.uop.dst_rtype === RT_FLT
  fregfile.io.write_ports(0).bits.addr := ll_wbarb.io.out.bits.uop.pdst
  fregfile.io.write_ports(0).bits.data := ll_wbarb.io.out.bits.data

  assert (ll_wbarb.io.in(0).ready) // never backpressure the memory unit.
  when (ifpu_resp.valid) { assert (ifpu_resp.bits.uop.dst_rtype === RT_FLT) }

  var w_cnt = 1
  for (i <- 1 until lsuWidth) {
    fregfile.io.write_ports(w_cnt).valid := RegNext(io.ll_wports(i).valid)
    fregfile.io.write_ports(w_cnt).bits.addr := RegNext(io.ll_wports(i).bits.uop.pdst)
    fregfile.io.write_ports(w_cnt).bits.data := recode(RegNext(io.ll_wports(i).bits.data),
                                                       RegNext(io.ll_wports(i).bits.uop.mem_size =/= 2.U))
    w_cnt += 1
  }
  for (eu <- exe_units) {
    fregfile.io.write_ports(w_cnt).valid     := eu.io_fpu_resp.valid && eu.io_fpu_resp.bits.uop.dst_rtype === RT_FLT
    fregfile.io.write_ports(w_cnt).bits.addr := eu.io_fpu_resp.bits.uop.pdst
    fregfile.io.write_ports(w_cnt).bits.data := eu.io_fpu_resp.bits.data
    w_cnt += 1
  }
  require (w_cnt == fregfile.io.write_ports.length)

  val fpiu_unit = exe_units.find(_.hasFpiu).get
  io.to_int <> fpiu_unit.io_fpiu_resp.get
  io.dgen   := fpiu_unit.io_dgen.get


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Commit Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  io.wakeups(0).valid := ll_wbarb.io.out.valid
  io.wakeups(0).bits := ll_wbarb.io.out.bits
  ll_wbarb.io.out.ready := true.B

  w_cnt = 1
  for (i <- 1 until lsuWidth) {
    io.wakeups(w_cnt) := io.ll_wports(i)
    io.wakeups(w_cnt).bits.data := recode(io.ll_wports(i).bits.data,
      io.ll_wports(i).bits.uop.mem_size =/= 2.U)
    w_cnt += 1
  }
  for (eu <- exe_units) {
    val exe_resp = eu.io_fpu_resp
    val wb_uop = eu.io_fpu_resp.bits.uop
    val wport = io.wakeups(w_cnt)
    wport.valid := exe_resp.valid && wb_uop.dst_rtype === RT_FLT
    wport.bits := exe_resp.bits

    w_cnt += 1
  }

  exe_units.map(_.io_fcsr_rm := io.fcsr_rm)
  exe_units.map(_.io_status := io.status)

  //-------------------------------------------------------------
  // **** Flush Pipeline ****
  //-------------------------------------------------------------
  // flush on exceptions, miniexeptions, and after some special instructions

  for (w <- 0 until exe_units.length) {
    exe_units(w).io_kill := io.flush_pipeline
  }

  override def toString: String =
    (BoomCoreStringPrefix("===FP Pipeline===") + "\n"
    + exe_units.map(_.toString).mkString("\n") + "\n"
    + fregfile.toString
    + BoomCoreStringPrefix(
      "Num Wakeup Ports      : " + numWakeupPorts))
}

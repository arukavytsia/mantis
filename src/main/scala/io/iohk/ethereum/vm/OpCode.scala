package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.domain.{Address, TxLogEntry, UInt256}
import io.iohk.ethereum.domain.UInt256._

// scalastyle:off magic.number
// scalastyle:off number.of.types
// scalastyle:off method.length
// scalastyle:off file.size.limit
object OpCodes {

  val LogOpCodes: List[OpCode] = List(
    LOG0,
    LOG1,
    LOG2,
    LOG3,
    LOG4)

  val SwapOpCodes: List[OpCode] = List(
    SWAP1,
    SWAP2,
    SWAP3,
    SWAP4,
    SWAP5,
    SWAP6,
    SWAP7,
    SWAP8,
    SWAP9,
    SWAP10,
    SWAP11,
    SWAP12,
    SWAP13,
    SWAP14,
    SWAP15,
    SWAP16)

  val DupOpCodes: List[OpCode] = List(
    DUP1,
    DUP2,
    DUP3,
    DUP4,
    DUP5,
    DUP6,
    DUP7,
    DUP8,
    DUP9,
    DUP10,
    DUP11,
    DUP12,
    DUP13,
    DUP14,
    DUP15,
    DUP16)

  val PushOpCodes: List[OpCode] = List(
    PUSH1,
    PUSH2,
    PUSH3,
    PUSH4,
    PUSH5,
    PUSH6,
    PUSH7,
    PUSH8,
    PUSH9,
    PUSH10,
    PUSH11,
    PUSH12,
    PUSH13,
    PUSH14,
    PUSH15,
    PUSH16,
    PUSH17,
    PUSH18,
    PUSH19,
    PUSH20,
    PUSH21,
    PUSH22,
    PUSH23,
    PUSH24,
    PUSH25,
    PUSH26,
    PUSH27,
    PUSH28,
    PUSH29,
    PUSH30,
    PUSH31,
    PUSH32)

  val FrontierOpCodes: List[OpCode] =
    LogOpCodes ++ SwapOpCodes ++ PushOpCodes ++ DupOpCodes ++ List(
      STOP,
      ADD,
      MUL,
      SUB,
      DIV,
      SDIV,
      MOD,
      SMOD,
      ADDMOD,
      MULMOD,
      EXP,
      SIGNEXTEND,

      LT,
      GT,
      SLT,
      SGT,
      EQ,
      ISZERO,
      AND,
      OR,
      XOR,
      NOT,
      BYTE,

      SHA3,

      ADDRESS,
      BALANCE,
      ORIGIN,
      CALLER,
      CALLVALUE,
      CALLDATALOAD,
      CALLDATASIZE,
      CALLDATACOPY,
      CODESIZE,
      CODECOPY,
      GASPRICE,
      EXTCODESIZE,
      EXTCODECOPY,

      BLOCKHASH,
      COINBASE,
      TIMESTAMP,
      NUMBER,
      DIFFICULTY,
      GASLIMIT,

      POP,
      MLOAD,
      MSTORE,
      MSTORE8,
      SLOAD,
      SSTORE,
      JUMP,
      JUMPI,
      PC,
      MSIZE,
      GAS,
      JUMPDEST,

      CREATE,
      CALL,
      CALLCODE,
      RETURN,
      INVALID,
      SELFDESTRUCT)

  val HomesteadOpCodes: List[OpCode] =
    DELEGATECALL +: FrontierOpCodes
}

object OpCode {
  def sliceBytes(bytes: ByteString, offset: UInt256, size: UInt256): ByteString = {
    val start = offset.min(bytes.size).toInt
    val end = (offset + size).min(bytes.size).toInt
    bytes.slice(start, end).padTo(size.toInt, 0.toByte)
  }
}

/**
  * Base class for all the opcodes of the EVM
  *
  * @param code Opcode byte representation
  * @param delta number of words to be popped from stack
  * @param alpha number of words to be pushed to stack
  */
abstract class OpCode(val code: Byte, val delta: Int, val alpha: Int, val constGasFn: FeeSchedule => BigInt) extends Product with Serializable {
  def this(code: Int, pop: Int, push: Int, constGasFn: FeeSchedule => BigInt) = this(code.toByte, pop, push, constGasFn)

  def execute[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    if (state.stack.size < delta)
      state.withError(StackUnderflow)
    else if (state.stack.size - delta + alpha > state.stack.maxSize)
      state.withError(StackOverflow)
    else {
      val constGas: BigInt = constGasFn(state.config.feeSchedule)

      val gas: BigInt = constGas + varGas(state)
      if (gas > state.gas)
        state.copy(gas = 0).withError(OutOfGas)
      else
        exec(state).spendGas(gas)
    }
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S]
}

sealed trait ConstGas { self: OpCode =>
  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = 0
}

case object STOP extends OpCode(0x00, 0, 0, _.G_zero) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] =
    state.halt
}

sealed abstract class UnaryOp(
    code: Int,
    constGasFn: FeeSchedule => BigInt)(val f: UInt256 => UInt256)
  extends OpCode(code, 1, 1, constGasFn) with ConstGas {

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (a, stack1) = state.stack.pop
    val res = f(a)
    val stack2 = stack1.push(res)
    state.withStack(stack2).step()
  }
}

sealed abstract class BinaryOp(code: Int, constGasFn: FeeSchedule => BigInt)(val f: (UInt256, UInt256) => UInt256)
  extends OpCode(code.toByte, 2, 1, constGasFn) {

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(a, b), stack1) = state.stack.pop(2)
    val res = f(a, b)
    val stack2 = stack1.push(res)
    state.withStack(stack2).step()
  }
}

sealed abstract class TernaryOp(code: Int, constGasFn: FeeSchedule => BigInt)(val f: (UInt256, UInt256, UInt256) => UInt256)
    extends OpCode(code.toByte, 3, 1, constGasFn) {

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(a, b, c), stack1) = state.stack.pop(3)
    val res = f(a, b, c)
    val stack2 = stack1.push(res)
    state.withStack(stack2).step()
  }
}

sealed abstract class ConstOp(code: Int)(val f: ProgramState[_ <: WorldStateProxy[_, _ <: Storage[_]], _ <: Storage[_]] => UInt256)
  extends OpCode(code, 0, 1, _.G_base) with ConstGas {

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val stack1 = state.stack.push(f(state))
    state.withStack(stack1).step()
  }
}

case object ADD extends BinaryOp(0x01, _.G_verylow)(_ + _) with ConstGas

case object MUL extends BinaryOp(0x02, _.G_low)(_ * _) with ConstGas

case object SUB extends BinaryOp(0x03, _.G_verylow)(_ - _) with ConstGas

case object DIV extends BinaryOp(0x04, _.G_low)(_ div _) with ConstGas

case object SDIV extends BinaryOp(0x05, _.G_low)(_ sdiv _) with ConstGas

case object MOD extends BinaryOp(0x06, _.G_low)(_ mod _) with ConstGas

case object SMOD extends BinaryOp(0x07, _.G_low)(_ smod _) with ConstGas

case object ADDMOD extends TernaryOp(0x08, _.G_mid)(_.addmod(_, _)) with ConstGas

case object MULMOD extends TernaryOp(0x09, _.G_mid)(_.mulmod(_, _)) with ConstGas

case object EXP extends BinaryOp(0x0a, _.G_exp)(_ ** _) {
  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(_, m: UInt256), _) = state.stack.pop(2)
    state.config.feeSchedule.G_expbyte * m.byteSize
  }
}

case object SIGNEXTEND extends BinaryOp(0x0b, _.G_low)((a, b) => b signExtend a) with ConstGas

case object LT extends BinaryOp(0x10, _.G_verylow)(_ < _) with ConstGas

case object GT extends BinaryOp(0x11, _.G_verylow)(_ > _) with ConstGas

case object SLT extends BinaryOp(0x12, _.G_verylow)(_ slt _) with ConstGas

case object SGT extends BinaryOp(0x13, _.G_verylow)(_ sgt _) with ConstGas

case object EQ extends BinaryOp(0x14, _.G_verylow)(_ == _) with ConstGas

case object ISZERO extends UnaryOp(0x15, _.G_verylow)(_.isZero) with ConstGas

case object AND extends BinaryOp(0x16, _.G_verylow)(_ & _) with ConstGas

case object OR extends BinaryOp(0x17, _.G_verylow)(_ | _) with ConstGas

case object XOR extends BinaryOp(0x18, _.G_verylow)(_ ^ _) with ConstGas

case object NOT extends UnaryOp(0x19, _.G_verylow)(~_) with ConstGas

case object BYTE extends BinaryOp(0x1a, _.G_verylow)((a, b) => b getByte a) with ConstGas

case object SHA3 extends OpCode(0x20, 2, 1, _.G_sha3) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(offset, size), stack1) = state.stack.pop(2)
    val (input, mem1) = state.memory.load(offset, size)
    val hash = kec256(input.toArray)
    val ret = UInt256(hash)
    val stack2 = stack1.push(ret)
    state.withStack(stack2).withMemory(mem1).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(offset, size), _) = state.stack.pop(2)
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val shaCost = state.config.feeSchedule.G_sha3word * wordsForBytes(size)
    memCost + shaCost
  }
}

case object ADDRESS extends ConstOp(0x30)(_.env.ownerAddr.toUInt256)

case object BALANCE extends OpCode(0x31, 1, 1, _.G_balance) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (accountAddress, stack1) = state.stack.pop
    val accountBalance = state.world.getBalance(Address(accountAddress))
    val stack2 = stack1.push(accountBalance)
    state.withStack(stack2).step()
  }
}

case object ORIGIN extends ConstOp(0x32)(_.env.originAddr.toUInt256)

case object CALLER extends ConstOp(0x33)(_.env.callerAddr.toUInt256)

case object CALLVALUE extends ConstOp(0x34)(_.env.value)

case object CALLDATALOAD extends OpCode(0x35, 1, 1, _.G_verylow) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (offset, stack1) = state.stack.pop
    val data = OpCode.sliceBytes(state.inputData, offset, 32)
    val stack2 = stack1.push(UInt256(data))
    state.withStack(stack2).step()
  }
}

case object CALLDATASIZE extends ConstOp(0x36)(_.inputData.size)

case object CALLDATACOPY extends OpCode(0x37, 3, 0, _.G_verylow) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(memOffset, dataOffset, size), stack1) = state.stack.pop(3)
    val data = OpCode.sliceBytes(state.inputData, dataOffset, size)
    val mem1 = state.memory.store(memOffset, data)
    state.withStack(stack1).withMemory(mem1).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(offset, _, size), _) = state.stack.pop(3)
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val copyCost = state.config.feeSchedule.G_copy * wordsForBytes(size)
    memCost + copyCost
  }
}

case object CODESIZE extends ConstOp(0x38)(_.env.program.length)

case object CODECOPY extends OpCode(0x39, 3, 0, _.G_verylow) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(memOffset, codeOffset, size), stack1) = state.stack.pop(3)
    val bytes = OpCode.sliceBytes(state.program.code, codeOffset, size)
    val mem1 = state.memory.store(memOffset, bytes)
    state.withStack(stack1).withMemory(mem1).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(offset, _, size), _) = state.stack.pop(3)
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val copyCost = state.config.feeSchedule.G_copy * wordsForBytes(size)
    memCost + copyCost
  }
}

case object GASPRICE extends ConstOp(0x3a)(_.env.gasPrice)

case object EXTCODESIZE extends OpCode(0x3b, 1, 1, _.G_extcode) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (addr, stack1) = state.stack.pop
    val codeSize = state.world.getCode(Address(addr)).size
    val stack2 = stack1.push(UInt256(codeSize))
    state.withStack(stack2).step()
  }
}

case object EXTCODECOPY extends OpCode(0x3c, 4, 0, _.G_extcode) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(address, memOffset, codeOffset, size), stack1) = state.stack.pop(4)
    val codeCopy = OpCode.sliceBytes(state.world.getCode(Address(address)), codeOffset, size)
    val mem1 = state.memory.store(memOffset, codeCopy)
    state.withStack(stack1).withMemory(mem1).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(_, memOffset, _, size), _) = state.stack.pop(4)
    val memCost = state.config.calcMemCost(state.memory.size, memOffset, size)
    val copyCost = state.config.feeSchedule.G_copy * wordsForBytes(size)
    memCost + copyCost
  }
}

case object BLOCKHASH extends OpCode(0x40, 1, 1, _.G_blockhash) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (blockNumber, stack1) = state.stack.pop

    val outOfLimits = state.env.blockHeader.number - blockNumber > 256 || blockNumber >= state.env.blockHeader.number
    val hash = if (outOfLimits) UInt256.Zero else state.world.getBlockHash(blockNumber).getOrElse(UInt256.Zero)

    val stack2 = stack1.push(hash)
    state.withStack(stack2).step()
  }
}

case object COINBASE extends ConstOp(0x41)(s => UInt256(s.env.blockHeader.beneficiary))

case object TIMESTAMP extends ConstOp(0x42)(s => UInt256(s.env.blockHeader.unixTimestamp))

case object NUMBER extends ConstOp(0x43)(s => UInt256(s.env.blockHeader.number))

case object DIFFICULTY extends ConstOp(0x44)(s => UInt256(s.env.blockHeader.difficulty))

case object GASLIMIT extends ConstOp(0x45)(s => UInt256(s.env.blockHeader.gasLimit))

case object POP extends OpCode(0x50, 1, 0, _.G_base) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (_, stack1) = state.stack.pop
    state.withStack(stack1).step()
  }
}

case object MLOAD extends OpCode(0x51, 1, 1, _.G_verylow) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (offset, stack1) = state.stack.pop
    val (word, mem1) = state.memory.load(offset)
    val stack2 = stack1.push(word)
    state.withStack(stack2).withMemory(mem1).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (offset, _) = state.stack.pop
    state.config.calcMemCost(state.memory.size, offset, UInt256.Size)
  }
}

case object MSTORE extends OpCode(0x52, 2, 0, _.G_verylow) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(offset, value), stack1) = state.stack.pop(2)
    val updatedMem = state.memory.store(offset, value)
    state.withStack(stack1).withMemory(updatedMem).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (offset, _) = state.stack.pop
    state.config.calcMemCost(state.memory.size, offset, UInt256.Size)
  }
}

case object SLOAD extends OpCode(0x54, 1, 1, _.G_sload) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (offset, stack1) = state.stack.pop
    val value = state.storage.load(offset)
    val stack2 = stack1.push(value)
    state.withStack(stack2).step()
  }
}

case object MSTORE8 extends OpCode(0x53, 2, 0, _.G_verylow) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(offset, value), stack1) = state.stack.pop(2)
    val valueToByte = (value mod 256).toByte
    val updatedMem = state.memory.store(offset, valueToByte)
    state.withStack(stack1).withMemory(updatedMem).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (offset, _) = state.stack.pop
    state.config.calcMemCost(state.memory.size, offset, 1)
  }
}

case object SSTORE extends OpCode(0x55, 2, 0, _.G_zero) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(offset, value), stack1) = state.stack.pop(2)
    val oldValue = state.storage.load(offset)
    val refund: BigInt = if (value.isZero && !oldValue.isZero) state.config.feeSchedule.R_sclear else 0
    val updatedStorage = state.storage.store(offset, value)
    state.withStack(stack1).withStorage(updatedStorage).refundGas(refund).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(offset, value), _) = state.stack.pop(2)
    val oldValue = state.storage.load(offset)
    if (oldValue.isZero && !value.isZero) state.config.feeSchedule.G_sset else state.config.feeSchedule.G_sreset
  }
}

case object JUMP extends OpCode(0x56, 1, 0, _.G_mid) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (pos, stack1) = state.stack.pop
    val dest = pos.toInt // fail with InvalidJump if convertion to Int is lossy

    if (pos == dest && state.program.validJumpDestinations.contains(dest))
      state.withStack(stack1).goto(dest)
    else
      state.withError(InvalidJump(pos))
  }
}

case object JUMPI extends OpCode(0x57, 2, 0, _.G_high) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(pos, cond), stack1) = state.stack.pop(2)
    val dest = pos.toInt // fail with InvalidJump if convertion to Int is lossy

    if(cond.isZero)
      state.withStack(stack1).step()
    else if (pos == dest && state.program.validJumpDestinations.contains(dest))
      state.withStack(stack1).goto(dest)
    else
      state.withError(InvalidJump(pos))
  }
}

case object PC extends ConstOp(0x58)(_.pc)

case object MSIZE extends ConstOp(0x59)(s => (UInt256.Size * wordsForBytes(s.memory.size)).toUInt256)

case object GAS extends ConstOp(0x5a)(state => (state.gas - state.config.feeSchedule.G_base).toUInt256)

case object JUMPDEST extends OpCode(0x5b, 0, 0, _.G_jumpdest) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    state.step()
  }
}

sealed abstract class PushOp(code: Int) extends OpCode(code, 0, 1, _.G_verylow) with ConstGas {
  val i: Int = code - 0x60

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val n = i + 1
    val bytes = state.program.getBytes(state.pc + 1, n)
    val word = UInt256(bytes)
    val stack1 = state.stack.push(word)
    state.withStack(stack1).step(n + 1)
  }
}

case object PUSH1  extends PushOp(0x60)
case object PUSH2  extends PushOp(0x61)
case object PUSH3  extends PushOp(0x62)
case object PUSH4  extends PushOp(0x63)
case object PUSH5  extends PushOp(0x64)
case object PUSH6  extends PushOp(0x65)
case object PUSH7  extends PushOp(0x66)
case object PUSH8  extends PushOp(0x67)
case object PUSH9  extends PushOp(0x68)
case object PUSH10 extends PushOp(0x69)
case object PUSH11 extends PushOp(0x6a)
case object PUSH12 extends PushOp(0x6b)
case object PUSH13 extends PushOp(0x6c)
case object PUSH14 extends PushOp(0x6d)
case object PUSH15 extends PushOp(0x6e)
case object PUSH16 extends PushOp(0x6f)
case object PUSH17 extends PushOp(0x70)
case object PUSH18 extends PushOp(0x71)
case object PUSH19 extends PushOp(0x72)
case object PUSH20 extends PushOp(0x73)
case object PUSH21 extends PushOp(0x74)
case object PUSH22 extends PushOp(0x75)
case object PUSH23 extends PushOp(0x76)
case object PUSH24 extends PushOp(0x77)
case object PUSH25 extends PushOp(0x78)
case object PUSH26 extends PushOp(0x79)
case object PUSH27 extends PushOp(0x7a)
case object PUSH28 extends PushOp(0x7b)
case object PUSH29 extends PushOp(0x7c)
case object PUSH30 extends PushOp(0x7d)
case object PUSH31 extends PushOp(0x7e)
case object PUSH32 extends PushOp(0x7f)


sealed abstract class DupOp private(code: Int, val i: Int) extends OpCode(code, i + 1, i + 2, _.G_verylow) with ConstGas {
  def this(code: Int) = this(code, code - 0x80)

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val stack1 = state.stack.dup(i)
    state.withStack(stack1).step()
  }
}

case object DUP1  extends DupOp(0x80)
case object DUP2  extends DupOp(0x81)
case object DUP3  extends DupOp(0x82)
case object DUP4  extends DupOp(0x83)
case object DUP5  extends DupOp(0x84)
case object DUP6  extends DupOp(0x85)
case object DUP7  extends DupOp(0x86)
case object DUP8  extends DupOp(0x87)
case object DUP9  extends DupOp(0x88)
case object DUP10 extends DupOp(0x89)
case object DUP11 extends DupOp(0x8a)
case object DUP12 extends DupOp(0x8b)
case object DUP13 extends DupOp(0x8c)
case object DUP14 extends DupOp(0x8d)
case object DUP15 extends DupOp(0x8e)
case object DUP16 extends DupOp(0x8f)


sealed abstract class SwapOp(code: Int, val i: Int) extends OpCode(code, i + 2, i + 2, _.G_verylow) with ConstGas {
  def this(code: Int) = this(code, code - 0x90)

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val stack1 = state.stack.swap(i + 1)
    state.withStack(stack1).step()
  }
}

case object SWAP1  extends SwapOp(0x90)
case object SWAP2  extends SwapOp(0x91)
case object SWAP3  extends SwapOp(0x92)
case object SWAP4  extends SwapOp(0x93)
case object SWAP5  extends SwapOp(0x94)
case object SWAP6  extends SwapOp(0x95)
case object SWAP7  extends SwapOp(0x96)
case object SWAP8  extends SwapOp(0x97)
case object SWAP9  extends SwapOp(0x98)
case object SWAP10 extends SwapOp(0x99)
case object SWAP11 extends SwapOp(0x9a)
case object SWAP12 extends SwapOp(0x9b)
case object SWAP13 extends SwapOp(0x9c)
case object SWAP14 extends SwapOp(0x9d)
case object SWAP15 extends SwapOp(0x9e)
case object SWAP16 extends SwapOp(0x9f)


sealed abstract class LogOp(code: Int, val i: Int) extends OpCode(code, i + 2, 0, _.G_log) {
  def this(code: Int) = this(code, code - 0xa0)

  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(offset, size, topics @ _*), stack1) = state.stack.pop(delta)
    val (data, memory) = state.memory.load(offset, size)
    val logEntry = TxLogEntry(state.env.ownerAddr, topics.map(_.bytes), data)

    state.withStack(stack1).withMemory(memory).withLog(logEntry).step()
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(offset, size, _*), _) = state.stack.pop(delta)
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val logCost = state.config.feeSchedule.G_logdata * size + i * state.config.feeSchedule.G_logtopic
    memCost + logCost
  }
}

case object LOG0 extends LogOp(0xa0)
case object LOG1 extends LogOp(0xa1)
case object LOG2 extends LogOp(0xa2)
case object LOG3 extends LogOp(0xa3)
case object LOG4 extends LogOp(0xa4)


abstract class CreateOp extends OpCode(0xf0, 3, 1, _.G_create) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(endowment, inOffset, inSize), stack1) = state.stack.pop(3)

    val validCall = state.env.callDepth < EvmConfig.MaxCallDepth && endowment <= state.ownBalance

    if (!validCall) {
      val stack2 = stack1.push(UInt256.Zero)
      state.withStack(stack2).step()
    } else {

      val (initCode, memory1) = state.memory.load(inOffset, inSize)
      val (newAddress, world1) = state.world.createAddressWithOpCode(state.env.ownerAddr)
      val world2 = world1.initialiseAccount(newAddress).transfer(state.env.ownerAddr, newAddress, endowment)

      // EIP-684
      val conflict = state.world.nonEmptyCodeOrNonceAccount(newAddress)
      val code = if (conflict) ByteString(INVALID.code) else initCode

      val newEnv = state.env.copy(
        callerAddr = state.env.ownerAddr,
        ownerAddr = newAddress,
        value = endowment,
        program = Program(code),
        inputData = ByteString.empty,
        callDepth = state.env.callDepth + 1
      )

      //FIXME: to avoid calculating this twice, we could adjust state.gas prior to execution in OpCode#execute
      //not sure how this would affect other opcodes [EC-243]
      val availableGas = state.gas - (constGasFn(state.config.feeSchedule) + varGas(state))
      val startGas = state.config.gasCap(availableGas)

      val context = ProgramContext[W, S](newEnv, newAddress, startGas, world2, state.config, state.addressesToDelete)
      val result = VM.run(context)

      val contractCode = result.returnData
      val codeDepositGas = state.config.calcCodeDepositCost(contractCode)
      val gasUsedInVm = startGas - result.gasRemaining
      val totalGasRequired = gasUsedInVm + codeDepositGas

      val maxCodeSizeExceeded = state.config.maxCodeSize.exists(codeSizeLimit => contractCode.size > codeSizeLimit)
      val enoughGasForDeposit = totalGasRequired <= startGas

      val creationFailed = maxCodeSizeExceeded || result.error.isDefined ||
        !enoughGasForDeposit && state.config.exceptionalFailedCodeDeposit

      if (creationFailed) {
        val stack2 = stack1.push(UInt256.Zero)
        state
          .withWorld(world1) // if creation fails at this point we still leave the creators nonce incremented
          .withStack(stack2)
          .spendGas(startGas).step()

      } else {
        val stack2 = stack1.push(newAddress.toUInt256)

        val state1 =
          if (!enoughGasForDeposit)
            state.withWorld(result.world).spendGas(gasUsedInVm)
          else {
            val world3 = result.world.saveCode(newAddress, result.returnData)
            val internalTx = InternalTransaction(CREATE, state.env.ownerAddr, None, startGas, initCode, endowment)
            state.withWorld(world3).spendGas(totalGasRequired).withInternalTxs(internalTx +: result.internalTxs)
          }

        state1
          .refundGas(result.gasRefund)
          .withStack(stack2)
          .withAddressesToDelete(result.addressesToDelete)
          .withLogs(result.logs)
          .withMemory(memory1)
          .step()
      }
    }
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(_, inOffset, inSize), _) = state.stack.pop(3)
    state.config.calcMemCost(state.memory.size, inOffset, inSize)
  }
}

case object CREATE extends CreateOp

abstract class CallOp(code: Int, delta: Int, alpha: Int) extends OpCode(code, delta, alpha, _.G_zero) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (params @ Seq(_, to, callValue, inOffset, inSize, outOffset, outSize), stack1) = getParams(state)

    val (inputData, mem1) = state.memory.load(inOffset, inSize)
    val endowment = if (this == DELEGATECALL) UInt256.Zero else callValue

    val startGas = calcStartGas(state, params, endowment)

    lazy val result = {
      val toAddr = Address(to)

      val (world1, owner, caller) = this match {
        case CALL =>
          val withTransfer = state.world.transfer(state.ownAddress, toAddr, endowment)
          (withTransfer, toAddr, state.ownAddress)

        case CALLCODE =>
          (state.world, state.ownAddress, state.ownAddress)

        case DELEGATECALL =>
          (state.world, state.ownAddress, state.env.callerAddr)
      }

      val env = state.env.copy(
        ownerAddr = owner,
        callerAddr = caller,
        inputData = inputData,
        value = callValue,
        program = Program(world1.getCode(toAddr)),
        callDepth = state.env.callDepth + 1)

      val context: ProgramContext[W, S] = state.context.copy(
        env = env,
        receivingAddr = toAddr,
        startGas = startGas,
        world = world1,
        initialAddressesToDelete = state.addressesToDelete)

      VM.run(context)
    }

    val validCall =
      state.env.callDepth < EvmConfig.MaxCallDepth &&
      endowment <= state.ownBalance

    if (!validCall || result.error.isDefined) {
      val stack2 = stack1.push(UInt256.Zero)

      val gasAdjustment: BigInt = if (validCall) 0 else -startGas
      val mem2 = mem1.expand(outOffset, outSize)

      val world1 = if (validCall) state.world.combineTouchedAccounts(result.world) else state.world

      state
        .withStack(stack2)
        .withMemory(mem2)
        .withWorld(world1)
        .spendGas(gasAdjustment)
        .step()

    } else {
      val stack2 = stack1.push(UInt256.One)
      val sizeCap = outSize.min(result.returnData.size).toInt
      val output = result.returnData.take(sizeCap)
      val mem2 = mem1.store(outOffset, output).expand(outOffset, outSize)
      val internalTx = internalTransaction(state.env, to, startGas, inputData, endowment)

      state
        .spendGas(-result.gasRemaining)
        .refundGas(result.gasRefund)
        .withStack(stack2)
        .withMemory(mem2)
        .withWorld(result.world)
        .withAddressesToDelete(result.addressesToDelete)
        .withInternalTxs(internalTx +: result.internalTxs)
        .withLogs(result.logs)
        .step()
    }
  }

  protected def internalTransaction(env: ExecEnv, callee: UInt256, startGas: BigInt, inputData: ByteString, endowment: UInt256): InternalTransaction = {
    val from = env.ownerAddr
    val to = if (this == CALL) Address(callee) else env.ownerAddr
    InternalTransaction(this, from, Some(to), startGas, inputData, endowment)
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(gas, to, callValue, inOffset, inSize, outOffset, outSize), _) = getParams(state)
    val endowment = if (this == DELEGATECALL) UInt256.Zero else callValue

    val memCost = calcMemCost(state, inOffset, inSize, outOffset, outSize)

    // FIXME: these are calculated twice (for gas and exec), especially account existence. Can we do better? [EC-243]
    val gExtra: BigInt = gasExtra(state, endowment, Address(to))
    val gCap: BigInt = gasCap(state, gas, gExtra + memCost)
    memCost + gCap + gExtra
  }

  protected def calcMemCost[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S],
      inOffset: UInt256, inSize: UInt256, outOffset: UInt256, outSize: UInt256): BigInt = {

    val memCostIn = state.config.calcMemCost(state.memory.size, inOffset, inSize)
    val memCostOut = state.config.calcMemCost(state.memory.size, outOffset, outSize)
    memCostIn max memCostOut
  }

  protected def getParams[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): (Seq[UInt256], Stack) = {
    val (Seq(gas, to), stack1) = state.stack.pop(2)
    val (value, stack2) = if (this == DELEGATECALL) (state.env.value, stack1) else stack1.pop
    val (Seq(inOffset, inSize, outOffset, outSize), stack3) = stack2.pop(4)
    Seq(gas, to, value, inOffset, inSize, outOffset, outSize) -> stack3
  }

  protected def calcStartGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Seq[UInt256], endowment: UInt256): BigInt = {
    val Seq(gas, to, _, inOffset, inSize, outOffset, outSize) = params
    val memCost = calcMemCost(state, inOffset, inSize, outOffset, outSize)
    val gExtra = gasExtra(state, endowment, Address(to))
    val gCap = gasCap(state, gas, gExtra + memCost)
    if (endowment.isZero) gCap else gCap + state.config.feeSchedule.G_callstipend
  }

  private def gasCap[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S], g: BigInt, consumedGas: BigInt): BigInt = {
    if (state.config.subGasCapDivisor.isDefined && state.gas >= consumedGas)
      g min state.config.gasCap(state.gas - consumedGas)
    else
      g
  }

  private def gasExtra[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S], endowment: UInt256, to: Address): BigInt = {

    val isValueTransfer = endowment > 0

    def postEip161CostCondition: Boolean =
      state.world.isAccountDead(to) && this == CALL && isValueTransfer

    def preEip161CostCondition: Boolean =
      !state.world.accountExists(to) && this == CALL

    val c_new: BigInt =
      if (state.config.noEmptyAccounts && postEip161CostCondition || !state.config.noEmptyAccounts && preEip161CostCondition)
        state.config.feeSchedule.G_newaccount else 0

    val c_xfer: BigInt = if (endowment.isZero) 0 else state.config.feeSchedule.G_callvalue
    state.config.feeSchedule.G_call + c_xfer + c_new
  }
}

case object CALL extends CallOp(0xf1, 7, 1)
case object CALLCODE extends CallOp(0xf2, 7, 1)
case object DELEGATECALL extends CallOp(0xf4, 6, 1)

case object RETURN extends OpCode(0xf3, 2, 0, _.G_zero) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(offset, size), stack1) = state.stack.pop(2)
    val (ret, mem1) = state.memory.load(offset, size)
    state.withStack(stack1).withReturnData(ret).withMemory(mem1).halt
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val (Seq(offset, size), _) = state.stack.pop(2)
    state.config.calcMemCost(state.memory.size, offset, size)
  }
}

case object INVALID extends OpCode(0xfe, 0, 0, _.G_zero) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] =
    state.withError(InvalidOpCode(code))
}

case object SELFDESTRUCT extends OpCode(0xff, 1, 0, _.G_selfdestruct) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (refund, stack1) = state.stack.pop
    val refundAddr: Address = Address(refund)
    val gasRefund: BigInt = if (state.addressesToDelete contains state.ownAddress) 0 else state.config.feeSchedule.R_selfdestruct

    val world =
      if (state.ownAddress == refundAddr)
        state.world.removeAllEther(state.ownAddress)
      else
        state.world.transfer(state.ownAddress, refundAddr, state.ownBalance)

    state
      .withWorld(world)
      .refundGas(gasRefund)
      .withAddressToDelete(state.ownAddress)
      .withStack(stack1)
      .halt
  }

  protected def varGas[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): BigInt = {
    val isValueTransfer = state.ownBalance > 0

    val (refundAddr, _) = state.stack.pop
    val refundAddress = Address(refundAddr)

    def postEip161CostCondition: Boolean =
        state.config.chargeSelfDestructForNewAccount &&
        isValueTransfer &&
        state.world.isAccountDead(refundAddress)

    def preEip161CostCondition: Boolean =
      state.config.chargeSelfDestructForNewAccount && !state.world.accountExists(refundAddress)

    if (state.config.noEmptyAccounts && postEip161CostCondition || !state.config.noEmptyAccounts && preEip161CostCondition)
      state.config.feeSchedule.G_newaccount else 0
  }
}

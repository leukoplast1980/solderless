#!/usr/bin/env python3
"""Differential test of the Solderless CPU core against Unicorn.

Generates random instruction sequences from templates, assembles them with
the GNU ARM assembler, runs each in both cores from the same random state,
and compares registers, flags and the data region.

Usage: diff_test.py LIBCMTEST.so [--cases N] [--seed S] [--verbose]
Needs: arm-none-eabi-as, arm-none-eabi-objcopy, arm-none-eabi-nm, unicorn.
"""
import argparse
import ctypes
import os
import random
import struct
import subprocess
import sys
import tempfile

from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS, UcError
from unicorn import arm_const as A

CODE_BASE, CODE_SIZE = 0x00000000, 0x10000
DATA_BASE, DATA_SIZE = 0x20000000, 0x10000
DATA_PTR = DATA_BASE + 0x8000      # base registers point here
STACK = DATA_BASE + 0xF000
CHUNK = 3000                         # cases assembled together, well inside CODE_SIZE

LO = [f"r{i}" for i in range(8)]
GP = [f"r{i}" for i in range(13)] + ["lr"]   # usable as general registers
CONDS = ["eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc", "hi", "ls", "ge", "lt", "gt", "le"]
SHIFTS = ["lsl", "lsr", "asr", "ror"]

SPECIAL = [0, 1, 2, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0xFFFFFFFE, 0x80000001,
           0x0000FFFF, 0xFFFF0000, 0x12345678, 0x00008000, 31, 32, 33, 0xFF, 0x100,
           0x80008000, 0x7FFF7FFF, 0x80807F7F]          # packed extremes for the DSP lanes


def rval(rng):
    r = rng.random()
    if r < 0.35:
        return rng.choice(SPECIAL)
    if r < 0.55:
        return rng.randrange(0, 64)
    return rng.getrandbits(32)


def expand_imm(imm12):
    """ThumbExpandImm (A5.3.2): the values a modified immediate can hold."""
    imm8 = imm12 & 0xFF
    if imm12 >> 10 == 0:
        k = (imm12 >> 8) & 3
        return [imm8, imm8 | imm8 << 16, imm8 << 8 | imm8 << 24,
                imm8 * 0x01010101][k]
    v = 0x80 | (imm12 & 0x7F)
    rot = imm12 >> 7
    return ((v >> rot) | (v << (32 - rot))) & 0xFFFFFFFF


def mod_imm(rng):
    while True:
        imm12 = rng.getrandbits(12)
        if imm12 >> 10 == 0 and (imm12 >> 8) & 3 and imm12 & 0xFF == 0:
            continue
        return expand_imm(imm12)


def r(rng, pool=GP, avoid=()):
    return rng.choice([x for x in pool if x not in avoid])


# ---------------------------------------------------------------------------
# Templates. Each returns (asm_lines, base_regs) where base_regs are
# registers that must point into the data region.
# ---------------------------------------------------------------------------

def t16_alu(rng):
    op = rng.choice(["ands", "eors", "lsls", "lsrs", "asrs", "adcs", "sbcs", "rors",
                     "tst", "cmp", "cmn", "orrs", "muls", "bics", "mvns"])
    a, b = r(rng, LO), r(rng, LO)
    if op == "muls":
        return [f"muls {a}, {b}, {a}"], []
    return [f"{op} {a}, {b}"], []


def t16_imm(rng):
    a, b = r(rng, LO), r(rng, LO)
    k = rng.randrange(6)
    if k == 0:
        return [f"{rng.choice(['lsls', 'lsrs', 'asrs'])} {a}, {b}, #{rng.randrange(1, 32)}"], []
    if k == 1:
        return [f"{rng.choice(['adds', 'subs'])} {a}, {b}, {r(rng, LO)}"], []
    if k == 2:
        return [f"{rng.choice(['adds', 'subs'])} {a}, {b}, #{rng.randrange(8)}"], []
    if k == 3:
        return [f"{rng.choice(['movs', 'cmp', 'adds', 'subs'])} {a}, #{rng.randrange(256)}"], []
    if k == 4:
        return [f"negs {a}, {b}"], []
    return [f"{rng.choice(['sxth', 'sxtb', 'uxth', 'uxtb', 'rev', 'rev16', 'revsh'])} {a}, {b}"], []


def t16_hi(rng):
    a, b = r(rng, GP), r(rng, GP)
    op = rng.choice(["add", "mov", "cmp"])
    if op == "cmp" and a in LO and b in LO:
        b = "r9"
    return [f"{op} {a}, {b}"], []


def t32_dp_shift(rng):
    op = rng.choice(["and", "bic", "orr", "orn", "eor", "add", "adc", "sbc", "sub", "rsb",
                     "tst", "teq", "cmp", "cmn", "mov", "mvn"])
    s = rng.choice(["", "s"]) if op not in ("tst", "teq", "cmp", "cmn") else ""
    d, n, m = r(rng), r(rng), r(rng)
    sh = rng.choice(SHIFTS + ["rrx", "none"])
    if sh == "none":
        shift = ""
    elif sh == "rrx":
        shift = ", rrx"
    else:
        amt = rng.randrange(1, 33 if sh in ("lsr", "asr") else 32)
        shift = f", {sh} #{amt}"
    if op in ("tst", "teq", "cmp", "cmn"):
        return [f"{op}.w {n}, {m}{shift}"], []
    if op == "mov":
        if sh == "none":
            return [f"mov{s}.w {d}, {m}"], []
        if sh == "rrx":
            return [f"rrx{s} {d}, {m}"], []
        return [f"{sh}{s}.w {d}, {m}, #{amt}"], []
    if op == "mvn":
        return [f"mvn{s}.w {d}, {m}{shift}"], []
    return [f"{op}{s}.w {d}, {n}, {m}{shift}"], []


def t32_dp_imm(rng):
    op = rng.choice(["and", "bic", "orr", "orn", "eor", "add", "adc", "sbc", "sub", "rsb",
                     "tst", "teq", "cmp", "cmn", "mov", "mvn"])
    s = rng.choice(["", "s"]) if op not in ("tst", "teq", "cmp", "cmn") else ""
    d, n = r(rng), r(rng)
    imm = mod_imm(rng)
    if op in ("tst", "teq", "cmp", "cmn"):
        return [f"{op}.w {n}, #{imm}"], []
    if op in ("mov", "mvn"):
        return [f"{op}{s}.w {d}, #{imm}"], []
    return [f"{op}{s}.w {d}, {n}, #{imm}"], []


def t32_plain(rng):
    d, n = r(rng), r(rng)
    k = rng.randrange(8)
    if k == 0:
        return [f"addw {d}, {n}, #{rng.randrange(4096)}"], []
    if k == 1:
        return [f"subw {d}, {n}, #{rng.randrange(4096)}"], []
    if k == 2:
        return [f"movw {d}, #{rng.randrange(65536)}"], []
    if k == 3:
        return [f"movt {d}, #{rng.randrange(65536)}"], []
    if k == 4:
        sh = rng.choice(["", f", lsl #{rng.randrange(1, 32)}", f", asr #{rng.randrange(1, 32)}"])
        if rng.random() < 0.5:
            return [f"ssat {d}, #{rng.randrange(1, 33)}, {n}{sh}"], []
        return [f"usat {d}, #{rng.randrange(0, 32)}, {n}{sh}"], []
    lsb = rng.randrange(32)
    w = rng.randrange(1, 33 - lsb)
    if k == 5:
        return [f"{rng.choice(['sbfx', 'ubfx'])} {d}, {n}, #{lsb}, #{w}"], []
    if k == 6:
        return [f"bfi {d}, {n}, #{lsb}, #{w}"], []
    return [f"bfc {d}, #{lsb}, #{w}"], []


def t32_reg(rng):
    d, n, m = r(rng), r(rng), r(rng)
    k = rng.randrange(4)
    if k == 0:
        return [f"{rng.choice(['lsl', 'lsr', 'asr', 'ror'])}{rng.choice(['', 's'])}.w {d}, {n}, {m}"], []
    if k == 1:
        op = rng.choice(["sxtah", "uxtah", "sxtab", "uxtab"])
        return [f"{op} {d}, {n}, {m}, ror #{rng.choice([0, 8, 16, 24])}"], []
    if k == 2:
        op = rng.choice(["sxth.w", "uxth.w", "sxtb.w", "uxtb.w"])
        return [f"{op} {d}, {m}, ror #{rng.choice([0, 8, 16, 24])}"], []
    return [f"{rng.choice(['rev.w', 'rev16.w', 'rbit', 'revsh.w', 'clz'])} {d}, {m}"], []


def t32_mul(rng):
    d, n, m, a = r(rng), r(rng), r(rng), r(rng)
    k = rng.randrange(4)
    if k == 0:
        return [f"mul {d}, {n}, {m}"], []
    if k == 1:
        return [f"{rng.choice(['mla', 'mls'])} {d}, {n}, {m}, {a}"], []
    if k == 2:
        lo, hi = rng.sample(GP, 2)
        return [f"{rng.choice(['smull', 'umull', 'smlal', 'umlal'])} {lo}, {hi}, {n}, {m}"], []
    return [f"{rng.choice(['sdiv', 'udiv'])} {d}, {n}, {m}"], []


def t_mem(rng):
    base = r(rng, LO)
    rt = r(rng, LO, avoid=(base,))
    k = rng.randrange(9)
    if k == 0:
        op = rng.choice(["ldr", "str"]); off = rng.randrange(32) * 4
        return [f"{op} {rt}, [{base}, #{off}]"], [base]
    if k == 1:
        op = rng.choice(["ldrb", "strb", "ldrh", "strh"])
        off = rng.randrange(32) * (2 if "h" in op else 1)
        return [f"{op} {rt}, [{base}, #{off}]"], [base]
    if k == 2:
        idx = r(rng, LO, avoid=(base, rt))
        op = rng.choice(["ldr", "str", "ldrb", "strb", "ldrh", "strh", "ldrsb", "ldrsh"])
        return [f"movs {idx}, #{rng.randrange(64) & (~3 if op in ('ldr', 'str') else ~0)}",
                f"{op} {rt}, [{base}, {idx}]"], [base]
    base = r(rng, GP)
    rt = r(rng, GP, avoid=(base,))
    if k == 3:
        op = rng.choice(["ldr.w", "str.w", "ldrb.w", "strb.w", "ldrh.w", "strh.w", "ldrsb.w", "ldrsh.w"])
        return [f"{op} {rt}, [{base}, #{rng.randrange(4096) & ~3}]"], [base]
    if k == 4:
        op = rng.choice(["ldr", "str", "ldrb", "strb", "ldrh", "strh", "ldrsb", "ldrsh"])
        off = rng.randrange(1, 256) & ~3 or 4
        form = rng.choice([f"[{base}, #-{off}]", f"[{base}, #{off}]!", f"[{base}, #-{off}]!",
                           f"[{base}], #{off}", f"[{base}], #-{off}"])
        return [f"{op} {rt}, {form}"], [base]
    if k == 5:
        idx = r(rng, GP, avoid=(base, rt))
        op = rng.choice(["ldr.w", "str.w", "ldrb.w", "strh.w", "ldrsh.w"])
        return [f"mov {idx}, #{rng.randrange(32)}", f"{op} {rt}, [{base}, {idx}, lsl #{rng.randrange(4)}]"], [base]
    if k == 6:
        rt2 = r(rng, GP, avoid=(base, rt))
        op = rng.choice(["ldrd", "strd"])
        off = rng.randrange(0, 64) * 4
        form = rng.choice([f"[{base}, #{off}]", f"[{base}, #-{off}]", f"[{base}, #{off}]!", f"[{base}], #{off}"])
        return [f"{op} {rt}, {rt2}, {form}"], [base]
    if k == 7:
        regs = sorted(rng.sample([x for x in GP if x not in (base,)], rng.randrange(2, 6)),
                      key=lambda x: 14 if x == "lr" else int(x[1:]))
        op = rng.choice(["ldmia", "stmia", "ldmdb", "stmdb"])
        wb = rng.choice(["", "!"])
        return [f"{op}.w {base}{wb}, {{{', '.join(regs)}}}"], [base]
    regs = sorted(rng.sample([f"r{i}" for i in range(13)], rng.randrange(1, 6)), key=lambda x: int(x[1:]))
    return [f"push {{{', '.join(regs)}}}", f"pop {{{', '.join(rng.sample(regs, len(regs)) and regs)}}}"], []


def t_it(rng):
    cond = rng.choice(CONDS)
    inv = {"eq": "ne", "ne": "eq", "cs": "cc", "cc": "cs", "mi": "pl", "pl": "mi", "vs": "vc",
           "vc": "vs", "hi": "ls", "ls": "hi", "ge": "lt", "lt": "ge", "gt": "le", "le": "gt"}
    pattern = rng.choice(["", "t", "e", "tt", "te", "et", "ee", "tte", "eet", "tet"])
    lines = [f"it{pattern} {cond}"]
    conds = [cond] + [cond if p == "t" else inv[cond] for p in pattern]
    for cc in conds:
        a, b = r(rng, LO), r(rng, LO)
        kind = rng.randrange(4)
        if kind == 0:
            lines.append(f"add{cc} {a}, {b}")
        elif kind == 1:
            lines.append(f"adds{cc}.w {a}, {b}, #{rng.randrange(256)}")
        elif kind == 2:
            lines.append(f"mov{cc} {a}, #{rng.randrange(256)}")
        else:
            lines.append(f"cmp{cc} {a}, {b}")
    return lines, []


def t_branch(rng):
    a = r(rng, LO)
    k = rng.randrange(4)
    if k == 0:
        return [f"b{rng.choice(CONDS)} 1f", "movs r0, #1", "1:"], []
    if k == 1:
        return [f"b{rng.choice(CONDS)}.w 1f", "movs r0, #1", "1:"], []
    if k == 2:
        return [f"{rng.choice(['cbz', 'cbnz'])} {a}, 1f", "movs r0, #1", "1:"], []
    return ["bl 1f", "movs r0, #1", "1:"], []


def t_tb(rng):
    idx = rng.randrange(3)
    return [f"movs r0, #{idx}", "tbb [pc, r0]", "0:", ".byte (2f-0b)/2, (3f-0b)/2, (4f-0b)/2, 0",
            "2: movs r1, #1", "b 5f", "3: movs r1, #2", "b 5f", "4: movs r1, #3", "5:"], []


# DSP extension

PAR = [p + o for p in ["s", "q", "sh", "u", "uq", "uh"] for o in ["add16", "asx", "sax", "sub16", "add8", "sub8"]]


def t_dsp(rng):
    d, n, m, a = r(rng), r(rng), r(rng), r(rng)
    k = rng.randrange(9)
    if k == 0:
        return [f"{rng.choice(PAR)} {d}, {n}, {m}"], []
    if k == 1 and rng.random() < 0.2:
        # packed extremes: 0x8000 * 0x8000 twice overflows SMUAD (Q). Not SMLAD: Unicorn's older QEMU
        # also sets Q when only the sum of the products overflows, which the ARM ARM (and QEMU since
        # 2021) does not
        return [f"movw {n}, #0x8000", f"movt {n}, #0x8000", f"{rng.choice(['smuad', 'smuadx'])} {d}, {n}, {n}"], []
    if k == 1:
        return [f"{rng.choice(['qadd', 'qdadd', 'qsub', 'qdsub'])} {d}, {m}, {n}", f"{rng.choice(PAR)} {a}, {n}, {m}", f"sel {d}, {a}, {n}"], []
    if k == 2:
        op = rng.choice(["sxtab16", "uxtab16"])
        if rng.random() < 0.4:
            return [f"{op[:2]}tb16 {d}, {m}, ror #{rng.choice([0, 8, 16, 24])}"], []
        return [f"{op} {d}, {n}, {m}, ror #{rng.choice([0, 8, 16, 24])}"], []
    if k == 3:
        xy = rng.choice(["bb", "bt", "tb", "tt"])
        return [rng.choice([f"smla{xy} {d}, {n}, {m}, {a}", f"smul{xy} {d}, {n}, {m}",
                            f"smlaw{xy[0]} {d}, {n}, {m}, {a}", f"smulw{xy[0]} {d}, {n}, {m}"])], []
    if k == 4:
        x = rng.choice(["", "x"])
        return [rng.choice([f"smlad{x} {d}, {n}, {m}, {a}", f"smuad{x} {d}, {n}, {m}",
                            f"smlsd{x} {d}, {n}, {m}, {a}", f"smusd{x} {d}, {n}, {m}"])], []
    if k == 5:
        rr = rng.choice(["", "r"])
        return [rng.choice([f"smmla{rr} {d}, {n}, {m}, {a}", f"smmul{rr} {d}, {n}, {m}",
                            f"smmls{rr} {d}, {n}, {m}, {a}", f"usad8 {d}, {n}, {m}", f"usada8 {d}, {n}, {m}, {a}"])], []
    if k == 6:
        lo, hi = r(rng), None
        hi = r(rng, avoid=(lo,))
        xy = rng.choice(["bb", "bt", "tb", "tt"])
        x = rng.choice(["", "x"])
        return [rng.choice([f"smlal{xy} {lo}, {hi}, {n}, {m}", f"smlald{x} {lo}, {hi}, {n}, {m}",
                            f"smlsld{x} {lo}, {hi}, {n}, {m}", f"umaal {lo}, {hi}, {n}, {m}"])], []
    if k == 7:
        return [rng.choice([f"ssat16 {d}, #{rng.randrange(1, 17)}, {n}", f"usat16 {d}, #{rng.randrange(0, 16)}, {n}",
                            f"pkhbt {d}, {n}, {m}, lsl #{rng.randrange(0, 32)}", f"pkhtb {d}, {n}, {m}, asr #{rng.randrange(1, 33)}"])], []
    return [f"msr {rng.choice(['APSR_g', 'APSR_nzcvqg'])}, {n}", f"{rng.choice(PAR)} {d}, {n}, {m}"], []


# Floating point

FP_SPECIAL = [0x00000000, 0x80000000, 0x7F800000, 0xFF800000, 0x7FC00000, 0xFFC00001, 0x7FA00000, 0x7F800001,
              0xFF812345, 0x00000001, 0x807FFFFF, 0x00400000, 0x00800000, 0x80800001, 0x7F7FFFFF, 0xFF7FFFFF,
              0x3F800000, 0xBF800000, 0x3F000000, 0x40000000, 0x4B000000, 0x4B7FFFFF, 0x4F000000, 0xCF000000,
              0x4F800000, 0x47800000, 0x477FE000, 0x33800000, 0x3FC00000, 0x3F7FFFFF, 0x3F800001, 0x7F000000,
              0x00FFFFFF, 0x0C000000, 0x38800000, 0x387FC000, 0x33000000]


def fval(rng):
    k = rng.random()
    if k < 0.3:
        return rng.choice(FP_SPECIAL)
    if k < 0.45:                                    # small integers and halves
        v = rng.randrange(-300, 300) / rng.choice([1, 2, 4, 8, 1024])
        return struct.unpack("<I", struct.pack("<f", v))[0]
    if k < 0.6:                                     # near a special: a few ulps away
        return (rng.choice(FP_SPECIAL) + rng.randrange(-3, 4)) & 0xFFFFFFFF
    if k < 0.75:                                    # tiny and huge exponents
        e = rng.choice([rng.randrange(0, 8), rng.randrange(100, 160), rng.randrange(240, 256)])
        return (rng.getrandbits(1) << 31) | (e << 23) | rng.getrandbits(23)
    return rng.getrandbits(32)


def fpscr_val(rng):
    v = rng.getrandbits(4) << 28 | rng.getrandbits(5) << 22 | rng.choice([0, 0, rng.getrandbits(8) & 0x9F])
    return v


def sreg(rng):
    return f"s{rng.randrange(32)}"


VMOV_IMM = ["1.0", "0.5", "2.0", "-1.0", "0.125", "31.0", "-0.1875", "1.9375", "16.0", "0.25", "-3.5", "-15.5"]


def vmov_scalar(dd, x, rt, to_core):
    """VMOV.32 Dd[x], Rt and Rt, Dn[x]: in the ARMv7-M FP instructions, but not in GAS for fpv4-sp-d16"""
    t = 14 if rt == "lr" else int(rt[1:])
    hw1 = 0xEE00 | (to_core << 4) | (x << 5) | dd
    hw2 = (t << 12) | 0x0B10
    return f".inst.w 0x{hw1:04x}{hw2:04x}"


def t_fp(rng):
    d, n, m = sreg(rng), sreg(rng), sreg(rng)
    k = rng.randrange(12)
    if k <= 2:
        return [f"{rng.choice(['vadd', 'vsub', 'vmul', 'vnmul', 'vdiv'])}.f32 {d}, {n}, {m}"], []
    if k == 3:
        return [f"{rng.choice(['vmla', 'vmls', 'vnmla', 'vnmls'])}.f32 {d}, {n}, {m}"], []
    if k == 4:
        return [f"{rng.choice(['vfma', 'vfms', 'vfnma', 'vfnms'])}.f32 {d}, {n}, {m}"], []
    if k == 5:
        return [rng.choice([f"vsqrt.f32 {d}, {m}", f"vabs.f32 {d}, {m}", f"vneg.f32 {d}, {m}", f"vmov.f32 {d}, {m}",
                            f"vmov.f32 {d}, #{rng.choice(VMOV_IMM)}"])], []
    if k == 6:
        return [rng.choice([f"vcmp.f32 {d}, {m}", f"vcmpe.f32 {d}, {m}", f"vcmp.f32 {d}, #0.0", f"vcmpe.f32 {d}, #0.0"]),
                "vmrs APSR_nzcv, fpscr"], []
    if k == 7:
        return [rng.choice([f"vcvt.s32.f32 {d}, {m}", f"vcvt.u32.f32 {d}, {m}", f"vcvtr.s32.f32 {d}, {m}",
                            f"vcvtr.u32.f32 {d}, {m}", f"vcvt.f32.s32 {d}, {m}", f"vcvt.f32.u32 {d}, {m}"])], []
    if k == 8:
        t = rng.choice(["s16", "u16", "s32", "u32"])
        fb = rng.randrange(1, (16 if t.endswith("16") else 32) + 1)
        return [rng.choice([f"vcvt.{t}.f32 {d}, {d}, #{fb}", f"vcvt.f32.{t} {d}, {d}, #{fb}"])], []
    if k == 9:
        return [f"{rng.choice(['vcvtb', 'vcvtt'])}.{rng.choice(['f32.f16', 'f16.f32'])} {d}, {m}"], []
    if k == 10:
        a, b = r(rng), r(rng)
        b2 = r(rng, avoid=(a,))
        dd = rng.randrange(16)
        sm = rng.randrange(31)
        return [rng.choice([f"vmov {d}, {a}", f"vmov {a}, {n}", f"vmov {a}, {b2}, s{sm}, s{sm + 1}",
                            f"vmov s{sm}, s{sm + 1}, {a}, {b}", f"vmov {a}, {b2}, d{dd}", f"vmov d{dd}, {a}, {b}",
                            vmov_scalar(dd, rng.randrange(2), a, 0), vmov_scalar(dd, rng.randrange(2), a, 1),
                            f"vmrs {a}, fpscr"])], []
    a = r(rng)
    return [f"vmsr fpscr, {a}", f"{rng.choice(['vadd', 'vmul', 'vdiv'])}.f32 {d}, {n}, {m}"], []


def t_fpmem(rng):
    base = r(rng, LO)
    k = rng.randrange(5)
    d = rng.randrange(16)
    off = rng.randrange(-32, 33) * 4
    if k == 0:
        return [f"{rng.choice(['vldr', 'vstr'])} {sreg(rng)}, [{base}, #{off}]"], [base]
    if k == 1:
        return [f"{rng.choice(['vldr', 'vstr'])} d{d}, [{base}, #{off}]"], [base]
    if k == 2:
        s0 = rng.randrange(32)
        cnt = rng.randrange(1, min(8, 32 - s0) + 1)
        regs = "{" + ", ".join(f"s{s0 + i}" for i in range(cnt)) + "}"
        return [rng.choice([f"vldmia {base}!, {regs}", f"vstmia {base}!, {regs}", f"vldmia {base}, {regs}",
                            f"vstmia {base}, {regs}", f"vldmdb {base}!, {regs}", f"vstmdb {base}!, {regs}"])], [base]
    if k == 3:
        cnt = rng.randrange(1, min(4, 16 - d) + 1)
        regs = "{" + ", ".join(f"d{d + i}" for i in range(cnt)) + "}"
        return [rng.choice([f"vldmia {base}!, {regs}", f"vstmdb {base}!, {regs}", f"vstmia {base}, {regs}"])], [base]
    s0 = rng.randrange(28)
    regs = "{" + ", ".join(f"s{s0 + i}" for i in range(rng.randrange(1, 5))) + "}"
    return [f"vpush {regs}", f"vadd.f32 s{s0}, s{s0}, s{(s0 + 5) % 32}", f"vpop {regs}"], []


TEMPLATES = [(t16_alu, 4), (t16_imm, 4), (t16_hi, 1), (t32_dp_shift, 6), (t32_dp_imm, 6),
             (t32_plain, 3), (t32_reg, 3), (t32_mul, 2), (t_mem, 6), (t_it, 2), (t_branch, 2),
             (t_tb, 1), (t_dsp, 6), (t_fp, 14)]
FP_TEMPLATES = (t_fp, t_fpmem)


def make_case(rng):
    """One to three templates. At most one memory template, and it comes
    first, so its base register still points into the data region."""
    lines, bases = [], set()
    fp = False
    if rng.random() < 0.3:
        mem = t_fpmem if rng.random() < 0.3 else t_mem
        lines, bs = mem(rng)
        bases = set(bs)
        fp = mem is t_fpmem
    others = [(t, w) for t, w in TEMPLATES if t is not t_mem]
    for _ in range(rng.randrange(0 if lines else 1, 3)):
        fn = rng.choices([t for t, _ in others], [w for _, w in others])[0]
        ls, _ = fn(rng)
        lines += ls
        fp = fp or fn in FP_TEMPLATES
    if fp:                      # the first FP instruction starts a new FP context: set FPSCR after it
        lines = ["vmsr fpscr, r12"] + lines
    # VCVT from fixed point rounds to nearest (FixedToFP with round_nearest in the ARM ARM);
    # Unicorn's older QEMU uses the FPSCR's rounding mode, so such a case sets no other FPSCR
    if any(l.startswith("vcvt.f32.") and "#" in l for l in lines) and sum(l.startswith("vmsr fpscr") for l in lines) > 1:
        return make_case(rng)
    return lines, bases


# ---------------------------------------------------------------------------

def assemble(cases, workdir):
    src = [".syntax unified", ".thumb", ".text", ".global _start", "_start:"]
    for i, (lines, _) in enumerate(cases):
        src.append(".balign 4")
        src.append(f"case{i}:")
        src += [("    " + l if not l.endswith(":") else l) for l in lines]
        src.append(f"case{i}_end:")
        src.append("    nop")
    path = os.path.join(workdir, "cases.s")
    with open(path, "w") as f:
        f.write("\n".join(src) + "\n")
    obj = os.path.join(workdir, "cases.o")
    binp = os.path.join(workdir, "cases.bin")
    subprocess.run(["arm-none-eabi-as", "-mcpu=cortex-m4", "-mfpu=fpv4-sp-d16", "-mthumb", "-o", obj, path], check=True)
    subprocess.run(["arm-none-eabi-objcopy", "-O", "binary", obj, binp], check=True)
    syms = {}
    out = subprocess.run(["arm-none-eabi-nm", obj], check=True, capture_output=True, text=True).stdout
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 3:
            syms[parts[2]] = int(parts[0], 16)
    with open(binp, "rb") as f:
        return f.read(), syms


class Mine:
    def __init__(self, lib):
        self.lib = lib
        lib.t_new.restype = ctypes.c_void_p
        for fn in ("t_free", "t_write", "t_read", "t_set_state", "t_get_state", "t_run_until", "t_set_fp", "t_get_fp"):
            getattr(lib, fn).argtypes = None
        self.h = ctypes.c_void_p(lib.t_new())

    def setup(self, code, data, regs, fp):
        self.lib.t_write(self.h, ctypes.c_uint32(CODE_BASE), code, ctypes.c_uint32(len(code)))
        self.lib.t_write(self.h, ctypes.c_uint32(DATA_BASE), data, ctypes.c_uint32(len(data)))
        arr = (ctypes.c_uint32 * 17)(*regs)
        self.lib.t_set_state(self.h, arr)
        self.lib.t_set_fp(self.h, (ctypes.c_uint32 * 33)(*fp, 0))

    def run(self, end):
        self.lib.t_run_until(self.h, ctypes.c_uint32(end), ctypes.c_uint32(64))
        out = (ctypes.c_uint32 * 19)()
        self.lib.t_get_state(self.h, out)
        data = ctypes.create_string_buffer(DATA_SIZE)
        self.lib.t_read(self.h, ctypes.c_uint32(DATA_BASE), data, ctypes.c_uint32(DATA_SIZE))
        fp = (ctypes.c_uint32 * 33)()
        self.lib.t_get_fp(self.h, fp)
        return list(out) + list(fp), data.raw


REGS = [getattr(A, f"UC_ARM_REG_R{i}") for i in range(13)] + [A.UC_ARM_REG_SP, A.UC_ARM_REG_LR, A.UC_ARM_REG_PC]


class Ref:
    def __init__(self):
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
        self.uc.ctl_set_cpu_model(A.UC_CPU_ARM_CORTEX_M4)
        self.uc.mem_map(CODE_BASE, CODE_SIZE)
        self.uc.mem_map(DATA_BASE, DATA_SIZE)

    def setup(self, code, data, regs, fp):
        for i in range(32):
            self.uc.reg_write(getattr(A, f"UC_ARM_REG_S{i}"), fp[i])
        self.uc.reg_write(A.UC_ARM_REG_FPSCR, 0)
        self.uc.mem_write(CODE_BASE, code)
        self.uc.mem_write(DATA_BASE, data)
        for i, rr in enumerate(REGS[:15]):
            self.uc.reg_write(rr, regs[i])
        self.uc.reg_write(A.UC_ARM_REG_XPSR, regs[16] | (1 << 24))
        self.uc.reg_write(A.UC_ARM_REG_APSR_NZCVQG, regs[16] & 0xF80F0000)

    def run(self, start, end):
        self.uc.emu_start(start | 1, end, count=64)
        regs = [self.uc.reg_read(rr) for rr in REGS]
        regs.append(self.uc.reg_read(A.UC_ARM_REG_XPSR))
        regs += [0, 0]
        regs += [self.uc.reg_read(getattr(A, f"UC_ARM_REG_S{i}")) for i in range(32)]
        regs.append(self.uc.reg_read(A.UC_ARM_REG_FPSCR))
        return regs, bytes(self.uc.mem_read(DATA_BASE, DATA_SIZE))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lib")
    ap.add_argument("--cases", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    cases = [make_case(rng) for _ in range(args.cases)]
    # Assembled in chunks: all of them at once would outgrow the 64 KB code region
    # (more than about 5000 cases), and the rest would not be there to run
    chunks = []
    for c0 in range(0, len(cases), CHUNK):
        with tempfile.TemporaryDirectory() as wd:
            code, syms = assemble(cases[c0:c0 + CHUNK], wd)
        if len(code) > CODE_SIZE:
            sys.exit(f"cases {c0}..: {len(code)} bytes of code, more than the {CODE_SIZE} mapped")
        chunks.append((c0, code, syms))

    mine = Mine(ctypes.CDLL(os.path.abspath(args.lib)))
    ref = Ref()
    failures = 0
    for i, (lines, bases) in enumerate(cases):
        c0, code, syms = chunks[i // CHUNK]
        if i == c0 and i:
            ref = Ref()      # a fresh Unicorn: it keeps translating the last chunk's code from its cache
        start, end = syms[f"case{i - c0}"], syms[f"case{i - c0}_end"]
        regs = [rval(rng) for _ in range(13)] + [STACK, rval(rng) | 1, start]
        for b in bases:
            regs[13 if b == "sp" else (14 if b == "lr" else int(b[1:]))] = DATA_PTR + rng.randrange(-64, 64) * 4
        xpsr = rng.getrandbits(5) << 27 | rng.getrandbits(4) << 16
        regs.append(xpsr)
        if lines and lines[0] == "vmsr fpscr, r12":
            regs[12] = fpscr_val(rng)
            # (see make_case) VCVT from fixed point: rounding to nearest whatever FPSCR says
            if any(l.startswith("vcvt.f32.") and "#" in l for l in lines):
                regs[12] &= ~(3 << 22)
        fp = [fval(rng) for _ in range(32)]
        data = bytes(rng.getrandbits(8) for _ in range(256)) * (DATA_SIZE // 256)

        mine.setup(code, data, regs, fp)
        ref.setup(code, data, regs, fp)
        try:
            rregs, rdata = ref.run(start, end)
        except UcError as e:
            if args.verbose:
                print(f"case {i}: unicorn error {e}, skipped: {lines}")
            continue
        mregs, mdata = mine.run(end)

        diffs = []
        names = [f"r{j}" for j in range(13)] + ["sp", "lr", "pc"]
        for j in range(16):
            if mregs[j] != rregs[j]:
                diffs.append(f"{names[j]}: mine {mregs[j]:08x} ref {rregs[j]:08x}")
        mflags, rflags = mregs[16] & 0xF80F0000, rregs[16] & 0xF80F0000
        if mflags != rflags:
            diffs.append(f"NZCVQ GE: mine {mflags >> 27:05b} {(mflags >> 16) & 15:04b} ref {rflags >> 27:05b} {(rflags >> 16) & 15:04b}")
        if mregs[17] or mregs[18]:
            diffs.append(f"fault: cfsr {mregs[17]:08x} lockup {mregs[18]}")
        for j in range(32):
            if mregs[19 + j] != rregs[19 + j]:
                diffs.append(f"s{j}: mine {mregs[19 + j]:08x} ref {rregs[19 + j]:08x} (in {fp[j]:08x})")
        if mregs[51] != rregs[51]:
            diffs.append(f"FPSCR: mine {mregs[51]:08x} ref {rregs[51]:08x}")
        if mdata != rdata:
            k = next(j for j in range(DATA_SIZE) if mdata[j] != rdata[j])
            diffs.append(f"memory differs at {DATA_BASE + k:08x}")
        if diffs:
            failures += 1
            if failures <= 30:
                print(f"case {i} FAILED: " + " | ".join(lines))
                print("   regs in: " + " ".join(f"{v:08x}" for v in regs))
                for d in diffs:
                    print("   " + d)
    print(f"{len(cases)} cases, {failures} failures")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()

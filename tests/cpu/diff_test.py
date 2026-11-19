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
import subprocess
import sys
import tempfile

from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS, UcError
from unicorn import arm_const as A

CODE_BASE, CODE_SIZE = 0x00000000, 0x10000
DATA_BASE, DATA_SIZE = 0x20000000, 0x10000
DATA_PTR = DATA_BASE + 0x8000      # base registers point here
STACK = DATA_BASE + 0xF000

LO = [f"r{i}" for i in range(8)]
GP = [f"r{i}" for i in range(13)] + ["lr"]   # usable as general registers
CONDS = ["eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc", "hi", "ls", "ge", "lt", "gt", "le"]
SHIFTS = ["lsl", "lsr", "asr", "ror"]

SPECIAL = [0, 1, 2, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0xFFFFFFFE, 0x80000001,
           0x0000FFFF, 0xFFFF0000, 0x12345678, 0x00008000, 31, 32, 33, 0xFF, 0x100]


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


TEMPLATES = [(t16_alu, 4), (t16_imm, 4), (t16_hi, 1), (t32_dp_shift, 6), (t32_dp_imm, 6),
             (t32_plain, 3), (t32_reg, 3), (t32_mul, 2), (t_mem, 6), (t_it, 2), (t_branch, 2),
             (t_tb, 1)]


def make_case(rng):
    """One to three templates. At most one memory template, and it comes
    first, so its base register still points into the data region."""
    lines, bases = [], set()
    if rng.random() < 0.3:
        lines, bs = t_mem(rng)
        bases = set(bs)
    others = [(t, w) for t, w in TEMPLATES if t is not t_mem]
    for _ in range(rng.randrange(0 if lines else 1, 3)):
        fn = rng.choices([t for t, _ in others], [w for _, w in others])[0]
        ls, _ = fn(rng)
        lines += ls
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
    subprocess.run(["arm-none-eabi-as", "-mcpu=cortex-m4", "-mthumb", "-o", obj, path], check=True)
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
        for fn in ("t_free", "t_write", "t_read", "t_set_state", "t_get_state", "t_run_until"):
            getattr(lib, fn).argtypes = None
        self.h = ctypes.c_void_p(lib.t_new())

    def setup(self, code, data, regs):
        self.lib.t_write(self.h, ctypes.c_uint32(CODE_BASE), code, ctypes.c_uint32(len(code)))
        self.lib.t_write(self.h, ctypes.c_uint32(DATA_BASE), data, ctypes.c_uint32(len(data)))
        arr = (ctypes.c_uint32 * 17)(*regs)
        self.lib.t_set_state(self.h, arr)

    def run(self, end):
        self.lib.t_run_until(self.h, ctypes.c_uint32(end), ctypes.c_uint32(64))
        out = (ctypes.c_uint32 * 19)()
        self.lib.t_get_state(self.h, out)
        data = ctypes.create_string_buffer(DATA_SIZE)
        self.lib.t_read(self.h, ctypes.c_uint32(DATA_BASE), data, ctypes.c_uint32(DATA_SIZE))
        return list(out), data.raw


REGS = [getattr(A, f"UC_ARM_REG_R{i}") for i in range(13)] + [A.UC_ARM_REG_SP, A.UC_ARM_REG_LR, A.UC_ARM_REG_PC]


class Ref:
    def __init__(self):
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
        self.uc.ctl_set_cpu_model(A.UC_CPU_ARM_CORTEX_M4)
        self.uc.mem_map(CODE_BASE, CODE_SIZE)
        self.uc.mem_map(DATA_BASE, DATA_SIZE)

    def setup(self, code, data, regs):
        self.uc.mem_write(CODE_BASE, code)
        self.uc.mem_write(DATA_BASE, data)
        for i, rr in enumerate(REGS[:15]):
            self.uc.reg_write(rr, regs[i])
        self.uc.reg_write(A.UC_ARM_REG_XPSR, regs[16] | (1 << 24))

    def run(self, start, end):
        self.uc.emu_start(start | 1, end, count=64)
        regs = [self.uc.reg_read(rr) for rr in REGS]
        regs.append(self.uc.reg_read(A.UC_ARM_REG_XPSR))
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
    with tempfile.TemporaryDirectory() as wd:
        code, syms = assemble(cases, wd)

    mine = Mine(ctypes.CDLL(os.path.abspath(args.lib)))
    ref = Ref()
    failures = 0
    for i, (lines, bases) in enumerate(cases):
        start, end = syms[f"case{i}"], syms[f"case{i}_end"]
        regs = [rval(rng) for _ in range(13)] + [STACK, rval(rng) | 1, start]
        for b in bases:
            regs[13 if b == "sp" else (14 if b == "lr" else int(b[1:]))] = DATA_PTR + rng.randrange(-64, 64) * 4
        xpsr = rng.getrandbits(4) << 28
        regs.append(xpsr)
        data = bytes(rng.getrandbits(8) for _ in range(256)) * (DATA_SIZE // 256)

        mine.setup(code, data, regs)
        ref.setup(code, data, regs)
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
        mflags, rflags = mregs[16] & 0xF8000000, rregs[16] & 0xF8000000
        if mflags != rflags:
            diffs.append(f"NZCVQ: mine {mflags >> 27:05b} ref {rflags >> 27:05b}")
        if mregs[17] or mregs[18]:
            diffs.append(f"fault: cfsr {mregs[17]:08x} lockup {mregs[18]}")
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

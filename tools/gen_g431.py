#!/usr/bin/env python3
"""Generates emu/gen/g431_data.c from ST's SVD and the stm32-data chip file.

    tools/gen_g431.py data/svd/STM32G431xx.svd data/chip/STM32G431RB.json emu/gen/g431_data.c

The output lists every peripheral of the STM32G431RB with its registers
(offset, reset value, writable bits), its RCC enable and reset bits and its
interrupts, plus the alternate-function table of the LQFP64 package.

Next to it, for the app: data/g431rb-pins.json (the package pins and their
functions) and data/g431rb-registers.json (every register and field, with
the notes in data/field-notes.txt). The generated files are committed; CI
checks that they are up to date.
"""
import fnmatch
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

# Peripherals in the SVD that are part of the core (handled by the CPU) or
# absent from the G431.
SKIP = {"FPU", "MPU", "STK", "SCB", "NVIC", "NVIC_STIR", "FPU_CPACR", "SCB_ACTLR",
        "AES", "DAC2", "DAC4", "ADC345_Common"}

# SVD names that differ from the stm32-data names
RENAME = {"DMAMUX": "DMAMUX1", "LPTIMER1": "LPTIM1", "USB_FS_device": "USB", "SAI": "SAI1",
          "ADC12_Common": "ADC12_COMMON", "FDCAN": "FDCANRAM1"}

# Known errors in ST's SVD reset values (RM0440 is the reference)
RESET_FIX = {
    ("RCC", "RCC_CR"): 0x00000500,      # HSION and HSIRDY; the SVD value is from the L4
    ("FLASH", "OPTR"): 0x7F8F70AA,      # factory option bytes, category 2 device
}

PACKAGE = "STM32G431RBTx"   # LQFP64


def num(s):
    s = s.strip().lower()
    return int(s, 16) if s.startswith("0x") else int(s)


def main():
    svd_path, chip_path, out_path = sys.argv[1:4]
    root = ET.parse(svd_path).getroot()
    svd = {p.findtext("name"): p for p in root.find("peripherals").findall("peripheral")}
    chip = json.load(open(chip_path))
    core = chip["cores"][0]
    cp = {p["name"]: p for p in core["peripherals"]}

    def base_of(p):
        return svd[p.get("derivedFrom")] if p.get("derivedFrom") else p

    # RCC register offsets and field bits, for enable/reset lookups
    rcc_regs = {}
    for rg in svd["RCC"].find("registers").findall("register"):
        fields = {f.findtext("name"): num(f.findtext("bitOffset"))
                  for f in rg.find("fields").findall("field")}
        rcc_regs[rg.findtext("name").replace("RCC_", "")] = (num(rg.findtext("addressOffset")), fields)

    def rcc_bit(info, kind):
        e = (info or {}).get("rcc", {}).get(kind)
        if not e:
            return -1, -1
        off, fields = rcc_regs[e["register"]]
        return off, fields[e["field"]]

    periphs = []
    for name, p in svd.items():
        if name in SKIP:
            continue
        cname = RENAME.get(name, name)
        info = cp.get(cname) or cp.get(cname + "1")
        if info is None and cname not in ("COMP", "OPAMP", "VREFBUF", "DBGMCU"):
            continue
        if cname == "COMP":
            info = cp["COMP1"]
        b = base_of(p)
        base = num(p.findtext("baseAddress"))
        ab = p.find("addressBlock")
        if ab is None:
            ab = b.find("addressBlock")
        size = num(ab.findtext("size"))
        regs = []
        for rg in b.find("registers").findall("register"):
            rname = rg.findtext("name")
            off = num(rg.findtext("addressOffset"))
            reset = num(rg.findtext("resetValue") or "0")
            access = rg.findtext("access") or b.findtext("access") or "read-write"
            fields = rg.find("fields")
            if access == "read-only":
                wmask = 0
            elif fields is None or not fields.findall("field"):
                wmask = 0xFFFFFFFF
            else:
                wmask = 0
                for f in fields.findall("field"):
                    fa = f.findtext("access") or access
                    w = num(f.findtext("bitWidth"))
                    o = num(f.findtext("bitOffset"))
                    if fa != "read-only":
                        wmask |= ((1 << w) - 1) << o
            reset = RESET_FIX.get((name, rname), reset)
            regs.append((off, reset & 0xFFFFFFFF, wmask & 0xFFFFFFFF, rname))
        regs.sort()
        en = rcc_bit(info, "enable")
        rst = rcc_bit(info, "reset")
        irqs = []
        for i in (info or {}).get("interrupts", []):
            n = next(x["number"] for x in core["interrupts"] if x["name"] == i["interrupt"])
            if n not in irqs:
                irqs.append(n)
        kind = (info or {}).get("registers", {}).get("kind", cname.lower())
        periphs.append(dict(name=cname, base=base, size=size, regs=regs, en=en, rst=rst,
                            irqs=irqs[:4], kind=kind, svd=name))

    periphs.sort(key=lambda x: x["base"])
    # Trim blocks so they do not overlap the next one
    for a, b in zip(periphs, periphs[1:]):
        if a["base"] + a["size"] > b["base"]:
            a["size"] = b["base"] - a["base"]

    # Alternate functions of the package pins
    pkg = next(p for p in chip["packages"] if p["name"] == PACKAGE)
    pins = []
    for pp in pkg["pins"]:
        for sig in pp["signals"]:
            if len(sig) >= 3 and sig[0] == "P" and sig[1] in "ABCDEFG" and sig[2:].split("-")[0].isdigit():
                pins.append((pp["position"], sig.split("-")[0]))
    pin_names = {pn for _, pn in pins}
    index = {p["name"]: i for i, p in enumerate(periphs)}
    afs = []
    analog = []
    for p in core["peripherals"]:
        for pin in p.get("pins", []):
            if pin["pin"] not in pin_names:
                continue
            if "af" not in pin:
                if p["name"].startswith(("ADC", "DAC", "COMP", "OPAMP")):
                    analog.append((ord(pin["pin"][1]) - ord("A"), int(pin["pin"][2:]), p["name"], pin["signal"]))
                continue
            pname = p["name"]
            if pname.startswith("COMP"):
                pidx = index.get("COMP", -1)
            elif pname.startswith("OPAMP"):
                pidx = index.get("OPAMP", -1)
            else:
                pidx = index.get(pname, -1)
            port = ord(pin["pin"][1]) - ord("A")
            num_ = int(pin["pin"][2:])
            afs.append((port, num_, pin["af"], pidx, pname, pin["signal"]))
    afs.sort()
    analog = sorted(set(analog))

    o = []
    o.append("/* Generated by tools/gen_g431.py from data/svd/STM32G431xx.svd (ST, Apache-2.0)")
    o.append(" * and data/chip/STM32G431RB.json (stm32-data, MIT OR Apache-2.0). Do not edit. */")
    o.append('#include "g431_data.h"')
    o.append("")
    for p in periphs:
        o.append(f"static const struct sl_reg_info regs_{p['name']}[] = {{")
        for off, reset, wmask, rname in p["regs"]:
            o.append(f"    {{ 0x{off:03X}, 0x{reset:08X}u, 0x{wmask:08X}u, \"{rname}\" }},")
        o.append("};")
    o.append("")
    o.append("const struct sl_periph_info sl_g431_periph[] = {")
    for p in periphs:
        irqs = ", ".join(str(i) for i in p["irqs"] + [-1] * (4 - len(p["irqs"])))
        o.append(f"    {{ \"{p['name']}\", \"{p['kind']}\", 0x{p['base']:08X}u, 0x{p['size']:X}u, "
                 f"regs_{p['name']}, {len(p['regs'])}, {p['en'][0]}, {p['en'][1]}, "
                 f"{p['rst'][0]}, {p['rst'][1]}, {{ {irqs} }} }},")
    o.append("};")
    o.append(f"const int sl_g431_nperiph = {len(periphs)};")
    o.append("")
    o.append("const struct sl_pin_info sl_g431_pins[] = {")
    for pos, pn in sorted(pins, key=lambda x: (ord(x[1][1]), int(x[1][2:]))):
        o.append(f"    {{ \"{pn}\", \"{pos}\", {ord(pn[1]) - ord('A')}, {int(pn[2:])} }},")
    o.append("};")
    o.append(f"const int sl_g431_npins = {len(pins)};")
    o.append("")
    o.append("const struct sl_af_info sl_g431_af[] = {")
    for port, n, af, pidx, pname, sig in afs:
        o.append(f"    {{ {port}, {n}, {af}, {pidx}, \"{pname}\", \"{sig}\" }},")
    o.append("};")
    o.append(f"const int sl_g431_naf = {len(afs)};")
    o.append("")
    o.append("/* Analog functions: ADC inputs, DAC outputs, comparator and op-amp pins */")
    o.append("const struct sl_analog_info sl_g431_analog[] = {")
    for port, n, pname, sig in analog:
        o.append(f"    {{ {port}, {n}, \"{pname}\", \"{sig}\" }},")
    o.append("};")
    o.append(f"const int sl_g431_nanalog = {len(analog)};")
    with open(out_path, "w") as f:
        f.write("\n".join(o) + "\n")

    data_dir = os.path.join(os.path.dirname(os.path.abspath(out_path)), "..", "..", "data")
    nregs, nfields = write_registers(svd, periphs, os.path.join(data_dir, "field-notes.txt"),
                                     os.path.join(data_dir, "g431rb-registers.json"))

    # The same pin data for the app (recommender, Board tab): data/g431rb-pins.json
    pkg_pins = {}
    for pp in pkg["pins"]:
        pkg_pins[int(pp["position"])] = pp["signals"]
    out = {"package": PACKAGE, "pins": []}
    for pos in sorted(pkg_pins):
        sigs = pkg_pins[pos]
        name = sigs[0].split("-")[0]
        if name.startswith("PG10"): name = "NRST"
        entry = {"number": pos, "name": name}
        if len(name) >= 3 and name[0] == "P" and name[1] in "ABCDEFG" and name[2:].isdigit():
            entry["port"] = ord(name[1]) - ord("A")
            entry["bit"] = int(name[2:])
            entry["af"] = [{"af": af, "periph": pn, "signal": sg} for port, n, af, pidx, pn, sg in afs
                           if port == entry["port"] and n == entry["bit"]]
            entry["analog"] = [f"{pn}_{sg}" for port, n, pn, sg in analog if port == entry["port"] and n == entry["bit"]]
        out["pins"].append(entry)
    json_path = os.path.join(data_dir, "g431rb-pins.json")
    with open(json_path, "w") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print(f"{len(periphs)} peripherals, {sum(len(p['regs']) for p in periphs)} registers, "
          f"{len(pins)} pins, {len(afs)} alternate functions, {len(analog)} analog functions; "
          f"for the app {nregs} registers with {nfields} fields")


# ---------------------------------------------------------------------------
# data/g431rb-registers.json: the Registers view's description of the chip

# Core peripherals shown with the chip's: SVD name, the name shown
CORE = [("STK", "SysTick"), ("NVIC", "NVIC"), ("SCB", "SCB")]

# What the emulator models; the rest only keep what is written to them
MODELLED = {"RCC", "PWR", "FLASH", "EXTI", "SYSCFG", "DBGMCU", "DAC1", "ADC1", "ADC2", "ADC12_COMMON",
            "USART1", "USART2", "USART3", "UART4", "LPUART1", "I2C1", "I2C2", "I2C3", "SPI1", "SPI2", "SPI3",
            "TIM1", "TIM2", "TIM3", "TIM4", "TIM6", "TIM7", "TIM8", "TIM15", "TIM16", "TIM17",
            "SysTick", "NVIC", "SCB"} | {f"GPIO{c}" for c in "ABCDEFG"}

ACCESS = {"read-only": "r", "write-only": "w", "read-write": "rw", "writeOnce": "w", "read-writeOnce": "rw"}


def clean(text):
    return re.sub(r"\s+", " ", (text or "").strip())


def read_notes(path):
    """[(peripheral, register, [field patterns], about, show, values or None)]"""
    notes, cur = [], None
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line[0].isspace():
            per, reg, fields = line.split()
            cur = dict(per=per, reg=reg, fields=fields.split(","), about="", show="", values=[], number=False)
            notes.append(cur)
            continue
        t = line.strip()
        if t == "(number)":
            cur["number"] = True
        elif t.startswith("show: "):
            cur["show"] = t[6:]
        elif " = " in t and re.fullmatch(r"[01x]+", t.split(" = ")[0]):
            k, v = t.split(" = ", 1)
            cur["values"].append([k, v])
        else:
            cur["about"] = (cur["about"] + " " + t).strip()
    return notes


def note_for(notes, owner, reg, field):
    for n in notes:
        if fnmatch.fnmatchcase(owner, n["per"]) and any(fnmatch.fnmatchcase(reg, r) for r in n["reg"].split(",")) \
                and any(fnmatch.fnmatchcase(field, f) for f in n["fields"]):
            return n
    return None


def write_registers(svd, periphs, notes_path, json_path):
    notes = read_notes(notes_path)
    used = set()

    def owner_of(name):
        p = svd[name]
        return p.get("derivedFrom") or name

    def block(owner, wide_timer):
        p = svd[owner]
        prefix = re.sub(r"\d+$", "", owner) + "_"
        regs = []
        extra = []
        if owner == "SCB":      # CPACR is described on its own in the SVD
            extra = [(r, 0x88) for r in svd["FPU_CPACR"].find("registers").findall("register")]
        for rg, at in [(r, None) for r in p.find("registers").findall("register")] + extra:
            rname = rg.findtext("name")
            off = num(rg.findtext("addressOffset")) if at is None else at
            racc = ACCESS.get(rg.findtext("access") or p.findtext("access") or "read-write", "rw")
            fields = []
            for f in (rg.find("fields").findall("field") if rg.find("fields") is not None else []):
                fields.append([f.findtext("name"), num(f.findtext("bitOffset")), num(f.findtext("bitWidth")),
                               ACCESS.get(f.findtext("access") or "", racc), clean(f.findtext("description")),
                               f.find("enumeratedValues")])
            names = {f[0] for f in fields}
            # ST's SVD has the F1's BRR (mantissa and fraction) for the USARTs
            if rname == "BRR" and "DIV_Mantissa" in names:
                fields = [["BRR", 0, 16, racc, "USARTDIV", None]]
            # TIM2 is the 32-bit one (TIM3 and TIM4 share its description)
            if wide_timer and (rname in ("CNT", "ARR") or re.fullmatch(r"CCR[1-4]", rname)):
                fields = [[rname, 0, 32, "rw", "", None]]
            out_fields = []
            for name, bit, width, acc, desc, ev in sorted(fields, key=lambda x: -x[1]):
                fd = {"n": name, "b": bit, "w": width}
                if acc != racc:
                    fd["a"] = acc
                if desc and desc.upper() != name.upper():
                    fd["d"] = desc
                values = None
                if ev is not None:
                    values = []
                    for v in ev.findall("enumeratedValue"):
                        val = num(v.findtext("value"))
                        if val >= 1 << width:
                            continue
                        values.append([format(val, f"0{width}b"), clean(v.findtext("description")) or v.findtext("name")])
                n = note_for(notes, owner, rname, name)
                if n:
                    used.add(id(n))
                    if n["about"]:
                        fd["about"] = n["about"]
                    if n["show"]:
                        fd["show"] = n["show"]
                    if n["values"]:
                        for k, _ in n["values"]:
                            if len(k) != width:
                                sys.exit(f"field-notes: {owner} {rname} {name}: value {k} is not {width} bits")
                        values = n["values"]
                    elif n["number"]:
                        values = None
                if values:
                    fd["v"] = values
                out_fields.append(fd)
            shown = rname[len(prefix):] if rname.startswith(prefix) else rname
            if shown == "CFSR_UFSR_BFSR_MMFSR":
                shown = "CFSR"
            reset = num(rg.findtext("resetValue") or "0") & 0xFFFFFFFF
            if owner == "TIM2" and not wide_timer and (rname in ("CNT", "ARR") or re.fullmatch(r"CCR[1-4]", rname)):
                reset &= 0xFFFF     # TIM3 and TIM4 count 16 bits
            rd = {"n": shown, "o": off, "r": reset, "a": racc}
            if clean(rg.findtext("description")):
                rd["d"] = clean(rg.findtext("description"))
            if out_fields:
                rd["f"] = out_fields
            regs.append(rd)
        regs.sort(key=lambda r: r["o"])
        return regs

    out = {"peripherals": [], "blocks": {}}
    entries = [(p["svd"], p["name"], p["base"], p["en"]) for p in periphs]
    entries += [(s, shown, num(svd[s].findtext("baseAddress")), (-1, -1)) for s, shown in CORE]
    ens = {shown: en for _, shown, _, en in entries}
    for sname, shown, base, en in entries:
        if shown == "FDCANRAM1":        # the message RAM is clocked with FDCAN1
            en = ens.get("FDCAN1", en)
        owner = owner_of(sname)
        wide = shown == "TIM2"
        key = owner + (" 32-bit" if wide else "")
        if key not in out["blocks"]:
            out["blocks"][key] = block(owner, wide)
        p = svd[sname]
        desc = clean(p.findtext("description")) or clean(svd[owner].findtext("description"))
        e = {"name": shown, "base": base, "block": key}
        if desc:
            e["d"] = desc
        if en[0] >= 0:
            e["en"] = [en[0], en[1]]
        if shown not in MODELLED:
            e["stored"] = True
        out["peripherals"].append(e)
    unused = [f"{n['per']} {n['reg']} {','.join(n['fields'])}" for n in notes if id(n) not in used]
    if unused:
        sys.exit("field-notes: these match no field: " + "; ".join(unused))
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
        f.write("\n")
    nregs = sum(len(b) for b in out["blocks"].values())
    nfields = sum(len(r.get("f", [])) for b in out["blocks"].values() for r in b)
    return nregs, nfields


if __name__ == "__main__":
    main()

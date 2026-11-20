#!/usr/bin/env python3
"""Generates emu/gen/g431_data.c from ST's SVD and the stm32-data chip file.

    tools/gen_g431.py data/svd/STM32G431xx.svd data/chip/STM32G431RB.json emu/gen/g431_data.c

The output lists every peripheral of the STM32G431RB with its registers
(offset, reset value, writable bits), its RCC enable and reset bits and its
interrupts, plus the alternate-function table of the LQFP64 package. The
generated file is committed; CI checks that it is up to date.
"""
import json
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
                            irqs=irqs[:4], kind=kind))

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

    # The same pin data for the app (recommender, Board tab): data/g431rb-pins.json
    import os
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
    json_path = os.path.join(os.path.dirname(os.path.abspath(out_path)), "..", "..", "data", "g431rb-pins.json")
    with open(json_path, "w") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print(f"{len(periphs)} peripherals, {sum(len(p['regs']) for p in periphs)} registers, "
          f"{len(pins)} pins, {len(afs)} alternate functions, {len(analog)} analog functions")


if __name__ == "__main__":
    main()

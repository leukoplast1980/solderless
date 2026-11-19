package io.github.leukoplast1980.solderless.board

import org.json.JSONArray
import org.json.JSONObject

/** The breadboard of one project: its parts and their pins. */
data class Board(val parts: List<Part> = emptyList()) {

    fun nextId() = (parts.maxOfOrNull { it.id } ?: 0) + 1

    fun with(p: Part) = Board(parts.filter { it.id != p.id } + p)
    fun without(id: Int) = Board(parts.filter { it.id != id })

    /** Applies a proposal: the new part plus the moves of other parts. */
    fun apply(pr: Recommender.Proposal): Board {
        var b = this
        for (m in pr.moves) {
            val q = b.parts.first { it.id == m.partId }
            b = b.with(q.copy(pins = q.pins.toMutableList().also { it[m.role] = m.to }))
        }
        return b.with(pr.part)
    }

    /** Name in wiring.fs: led, led2, rgb, button, ... */
    fun nameOf(p: Part): String {
        val same = parts.filter { it.type == p.type }.sortedBy { it.id }
        val i = same.indexOfFirst { it.id == p.id }
        return if (i <= 0) p.type.prefix else p.type.prefix + (i + 1)
    }

    // ------------------------------------------------------------------
    // What the emulator needs: one attachment per terminal (sl_attach)

    fun attachments(chip: ChipPins): IntArray {
        val out = ArrayList<Int>()
        fun att(part: Int, role: Int, pin: String, kind: Int, toVcc: Boolean, r: Int, vf: Int, bounce: Int) {
            val p = chip.gpio(pin) ?: return
            out += listOf(part, role, p.slot, kind, if (toVcc) 1 else 0, r, vf, bounce)
        }
        for (p in parts) for ((r, u) in p.pins.withIndex()) when (p.type) {
            PartType.LED, PartType.RGB_LED ->
                att(p.id, r, u.pin, ATT_LED, !p.activeHigh, Passives.ledResistor(p.vfMv(r), p.currentMa), p.vfMv(r), 0)
            PartType.BUTTON -> {
                att(p.id, r, u.pin, ATT_SWITCH, p.toVcc, 0, 0, p.bounceUs)
                if (p.externalPull) att(p.id, r + 1, u.pin, ATT_PULL, !p.toVcc, 10000, 0, 0)
            }
            PartType.POT -> att(p.id, r, u.pin, ATT_POT, false, 10000, 0, 0)
            PartType.SPEAKER, PartType.PIEZO -> att(p.id, r, u.pin, ATT_AUDIO, false, 0, 0, 0)
        }
        return out.toIntArray()
    }

    // ------------------------------------------------------------------
    // Saved with the project as board.json

    fun toJson(): String = JSONObject().put("v", 1).put("parts", JSONArray().also { a ->
        for (p in parts) a.put(JSONObject()
            .put("id", p.id).put("type", p.type.name)
            .put("pins", JSONArray().also { pa -> p.pins.forEach { pa.put(useToJson(it)) } })
            .put("colour", p.colour.name).put("sizeMm", p.sizeMm).put("currentMa", p.currentMa)
            .put("activeHigh", p.activeHigh).put("dimmable", p.dimmable).put("toVcc", p.toVcc)
            .put("externalPull", p.externalPull).put("bounceUs", p.bounceUs).put("interrupt", p.interrupt)
            .put("dacDrive", p.dacDrive).put("pinned", p.pinnedOverTabs))
    }).toString(1)

    companion object {
        const val ATT_LED = 1
        const val ATT_SWITCH = 2
        const val ATT_PULL = 3
        const val ATT_POT = 4
        const val ATT_AUDIO = 5

        private fun useToJson(u: PinUse): JSONObject {
            val o = JSONObject().put("pin", u.pin)
            when (val f = u.function) {
                is Function.Gpio -> o.put("fn", "gpio")
                is Function.Timer -> o.put("fn", "timer").put("timer", f.timer).put("ch", f.channel).put("af", f.af)
                is Function.Adc -> o.put("fn", "adc").put("adc", f.adc).put("ch", f.channel)
                is Function.Dac -> o.put("fn", "dac").put("ch", f.channel)
            }
            return o
        }

        private fun useFromJson(o: JSONObject) = PinUse(o.getString("pin"), when (o.getString("fn")) {
            "timer" -> Function.Timer(o.getString("timer"), o.getInt("ch"), o.getInt("af"))
            "adc" -> Function.Adc(o.getString("adc"), o.getInt("ch"))
            "dac" -> Function.Dac(o.getInt("ch"))
            else -> Function.Gpio
        })

        fun fromJson(text: String): Board {
            val a = JSONObject(text).optJSONArray("parts") ?: return Board()
            return Board((0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val type = runCatching { PartType.valueOf(o.getString("type")) }.getOrNull() ?: return@mapNotNull null
                val pa = o.getJSONArray("pins")
                Part(
                    id = o.getInt("id"), type = type,
                    pins = (0 until pa.length()).map { useFromJson(pa.getJSONObject(it)) },
                    colour = runCatching { LedColour.valueOf(o.optString("colour")) }.getOrDefault(LedColour.RED),
                    sizeMm = o.optInt("sizeMm", 5), currentMa = o.optInt("currentMa", 5),
                    activeHigh = o.optBoolean("activeHigh", true), dimmable = o.optBoolean("dimmable", false),
                    toVcc = o.optBoolean("toVcc", false), externalPull = o.optBoolean("externalPull", false),
                    bounceUs = o.optInt("bounceUs", 2000), interrupt = o.optBoolean("interrupt", true),
                    dacDrive = o.optBoolean("dacDrive", true), pinnedOverTabs = o.optBoolean("pinned", false),
                )
            })
        }
    }
}

package io.github.leukoplast1980.solderless.board

/**
 * The smart header order: each part's pins on adjacent rows beside the part,
 * so no wire has to cross another to reach it. Parts go top to bottom in the
 * order given, shared out between the two headers; free pins fill the rows
 * between them, in pin-number order, and the supply pins come last.
 */
object SmartOrder {
    /**
     * The rows one part takes: its [pins] in this order, [gap] free rows between
     * neighbours, centred in at least [span] rows. [reach] is how far the part
     * reaches in from its header (any unit), so that two parts facing each other
     * across the board are not given the same rows when together they reach
     * further than [layout]'s width.
     */
    class Unit(val key: Int, val pins: List<String>, val span: Int, val gap: Int = 0, val reach: Float = 0f)

    class Layout(
        val left: List<ChipPins.Pin>,
        val right: List<ChipPins.Pin>,
        /** Per unit key: +1 on the left header, -1 on the right */
        val side: Map<Int, Int>,
        /** Per unit key: the middle of its rows, in rows from the top (row i's centre is i + 0.5) */
        val centre: Map<Int, Float>,
    )

    /**
     * [groups]: the units that stay together on one side, one after another (a
     * bus and the parts on it), in the order to place them. [width]: the room
     * between the headers, in the units of [Unit.reach]. Null when they do not fit.
     */
    fun layout(chip: ChipPins, groups: List<List<Unit>>, width: Float = Float.MAX_VALUE): Layout? {
        val byName = chip.pins.associateBy { it.name }
        val rows = (chip.pins.size + 1) / 2
        // Pins the chip has, each in the first unit that names it
        val taken = HashSet<String>()
        val gs = groups.map { g ->
            g.map { u -> Unit(u.key, u.pins.filter { it in byName && taken.add(it) }, u.span, u.gap, u.reach) }
        }
        fun body(u: Unit) = if (u.pins.isEmpty()) 0 else u.pins.size + (u.pins.size - 1) * u.gap
        // Rows a unit gets: room for its pins, with as many free rows above as below
        fun rowsOf(u: Unit): Int {
            val b = body(u)
            var n = maxOf(u.span, b, 1)
            if (b > 0 && (n - b) % 2 != 0) n++
            return n
        }

        class Placed(val start: Int, val end: Int, val reach: Float)
        val slots = List(2) { arrayOfNulls<ChipPins.Pin>(rows) }
        val placed = List(2) { ArrayList<Placed>() }
        val next = intArrayOf(0, 0)
        val side = HashMap<Int, Int>()
        val centre = HashMap<Int, Float>()

        // The first row from [from] where the group fits on side s, or -1
        fun fit(g: List<Unit>, s: Int, from: Int): Int {
            var start = from
            while (true) {
                var row = start
                var clash = -1
                for (u in g) {
                    val end = row + rowsOf(u)
                    if (end > rows) return -1
                    // facing parts that together reach across: not on the same rows
                    val hit = placed[1 - s].filter { it.start < end && row < it.end && it.reach + u.reach > width }
                        .maxOfOrNull { it.end }
                    if (hit != null) { clash = hit; break }
                    row = end
                }
                if (clash < 0) return start
                start = clash
            }
        }

        for (g in gs) {
            val first = if (next[0] <= next[1]) 0 else 1
            var s = first
            var start = fit(g, s, next[s])
            if (start < 0) { s = 1 - first; start = fit(g, s, next[s]) }
            if (start < 0) return null
            var row = start
            for (u in g) {
                val n = rowsOf(u)
                var r = row + (n - body(u)) / 2
                for ((i, name) in u.pins.withIndex()) {
                    if (i > 0) r += u.gap
                    slots[s][r++] = byName[name]
                }
                placed[s] += Placed(row, row + n, u.reach)
                side[u.key] = if (s == 0) 1 else -1
                centre[u.key] = row + n / 2f
                row += n
            }
            next[s] = row
        }

        // The free pins fill the rest: left header first, top down
        val free = ArrayDeque(chip.pins.filter { it.name !in taken }.sortedWith(compareBy({ !it.isGpio }, { it.number })))
        for (s in 0..1) for (i in 0 until rows) if (slots[s][i] == null) slots[s][i] = free.removeFirstOrNull()
        return Layout(slots[0].filterNotNull(), slots[1].filterNotNull(), side, centre)
    }
}

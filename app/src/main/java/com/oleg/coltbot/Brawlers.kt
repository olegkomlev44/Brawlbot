package com.oleg.coltbot

/** Роль бравлера в бою (от неё зависит, как с ним драться). */
object Role {
    const val DMG = 0; const val TANK = 1; const val SNIPER = 2; const val THROWER = 3
    const val ASSASSIN = 4; const val SUPPORT = 5; const val CONTROL = 6
    val NAMES = arrayOf("боец", "танк", "снайпер", "метатель", "ассасин", "поддержка", "контроль")
}

/**
 * Паспорт бравлера. Значения приблизительные (по вики, макс. уровень): достаточно, чтобы отличать
 * снайпера от танка, а быстрого ассасина от медленного метателя. Правятся прямо в таблице ниже.
 *  rng   - дальность основной атаки в клетках
 *  spd   - скорость (ед/с, у Кольта 720)
 *  hp    - здоровье
 *  burst - опасность вблизи, 0..1 (дробовики и рукопашные = 1)
 *  dash  - на сколько клеток может внезапно сблизиться (рывок/прыжок/крюк), 0 = нет
 */
class BInfo(val key: String, val rng: Float, val spd: Int, val hp: Int, val role: Int, val burst: Float, val dash: Float)

object Brawlers {
    const val COLT_RANGE_TILES = 10f          // дальность самого Кольта в клетках (его дальность в кадре = Layout.SHOOT)
    private val db = HashMap<String, BInfo>()
    private fun b(key: String, rng: Float, spd: Int, hp: Int, role: Int, burst: Float, dash: Float = 0f) {
        db[key] = BInfo(key, rng, spd, hp, role, burst, dash)
    }

    // названия классов модели -> ключи таблицы
    private val alias = mapOf(
        "jesse" to "jessie", "penne" to "penny", "nockout" to "knockout", "shede" to "shade",
        "colonel-ruffs" to "ruffs", "el-primo" to "elprimo", "mr-p" to "mrp", "8-bit" to "8bit"
    )

    init {
        //   имя         дальн  скор   хп    роль            урон  рывок
        b("colt",        10f,   720,  5600,  Role.DMG,       0.8f)
        b("shelly",      7.7f,  770,  7400,  Role.DMG,       1.0f)
        b("nita",        5.5f,  720,  8000,  Role.DMG,       0.7f)
        b("bull",        5.5f,  770, 10000,  Role.TANK,      1.0f, 5f)
        b("brock",       11f,   720,  4800,  Role.SNIPER,    0.6f)
        b("elprimo",     2.7f,  770, 12000,  Role.TANK,      0.9f, 6f)
        b("barley",      9f,    720,  4800,  Role.THROWER,   0.5f)
        b("poco",        7f,    720,  7400,  Role.SUPPORT,   0.5f)
        b("rosa",        3.7f,  770, 10000,  Role.TANK,      0.9f)
        b("jessie",      9f,    720,  6000,  Role.CONTROL,   0.5f)
        b("dynamike",    7.3f,  770,  5600,  Role.THROWER,   0.7f)
        b("tick",        7.3f,  720,  4400,  Role.THROWER,   0.6f)
        b("8bit",        9f,    580, 10000,  Role.DMG,       0.7f)
        b("rico",        9.7f,  720,  5600,  Role.DMG,       0.7f)
        b("darryl",      6f,    770, 10600,  Role.TANK,      1.0f, 5f)
        b("penny",       8.7f,  720,  6400,  Role.THROWER,   0.6f)
        b("carl",        7.5f,  720,  8000,  Role.DMG,       0.7f)
        b("jacky",       3.3f,  770, 10000,  Role.TANK,      0.7f)
        b("gus",         9.3f,  720,  6400,  Role.SUPPORT,   0.5f)
        b("bo",          8.7f,  720,  7200,  Role.CONTROL,   0.6f)
        b("emz",         6.7f,  720,  7200,  Role.CONTROL,   0.7f)
        b("stu",         7.7f,  720,  5800,  Role.ASSASSIN,  0.7f, 4f)
        b("piper",       12f,   720,  4800,  Role.SNIPER,    0.6f)
        b("pam",         9f,    720,  9600,  Role.SUPPORT,   0.8f)
        b("frank",       6f,    770, 14000,  Role.TANK,      1.0f)
        b("bibi",        3.7f,  820,  9600,  Role.TANK,      0.8f)
        b("bea",         12f,   720,  4800,  Role.SNIPER,    0.6f)
        b("nani",        11f,   720,  4800,  Role.SNIPER,    0.6f)
        b("edgar",       2.5f,  770,  6000,  Role.ASSASSIN,  0.9f, 5f)
        b("mortis",      3.7f,  820,  7800,  Role.ASSASSIN,  0.8f, 5f)
        b("leon",        9.7f,  770,  6400,  Role.ASSASSIN,  0.8f)
        b("crow",        8.7f,  770,  4000,  Role.ASSASSIN,  0.5f)
        b("spike",       6.7f,  720,  4800,  Role.DMG,       0.7f)
        b("sandy",       6f,    720,  7800,  Role.CONTROL,   0.6f)
        b("amber",       8.3f,  720,  6000,  Role.DMG,       0.6f)
        b("gale",        8.3f,  720,  6000,  Role.CONTROL,   0.6f)
        b("max",         8.3f,  800,  5600,  Role.SUPPORT,   0.6f)
        b("griff",       8.3f,  720,  6000,  Role.DMG,       0.7f)
        b("lou",         9.3f,  720,  6000,  Role.CONTROL,   0.5f)
        b("eve",         9.3f,  720,  6000,  Role.DMG,       0.6f)
        b("squeak",      8.7f,  720,  6400,  Role.THROWER,   0.6f)
        b("surge",       6.7f,  720,  6000,  Role.DMG,       0.7f)
        b("colette",     7.7f,  720,  6400,  Role.DMG,       0.6f)
        b("belle",       13f,   720,  5200,  Role.SNIPER,    0.5f)
        b("byron",       12f,   720,  5200,  Role.SNIPER,    0.4f)
        b("ruffs",       9f,    720,  5600,  Role.SUPPORT,   0.6f)
        b("otis",        9f,    720,  6400,  Role.CONTROL,   0.5f)
        b("sprout",      8f,    720,  6400,  Role.THROWER,   0.6f)
        b("tara",        7f,    720,  6000,  Role.DMG,       0.7f)
        b("gene",        8.3f,  720,  6000,  Role.SUPPORT,   0.6f)
        b("meg",         7.3f,  720,  8000,  Role.DMG,       0.8f)
        b("lola",        8f,    720,  6000,  Role.DMG,       0.6f)
        b("grom",        7.3f,  720,  6000,  Role.THROWER,   0.6f)
        b("fang",        3.5f,  770,  6400,  Role.ASSASSIN,  0.8f, 3f)
        b("janet",       8.7f,  720,  5200,  Role.SNIPER,    0.5f)
        b("buzz",        3f,    770,  8800,  Role.ASSASSIN,  0.8f, 8f)
        b("ash",         5f,    720,  9000,  Role.TANK,      0.8f)
        b("bonnie",      8.3f,  720,  6400,  Role.DMG,       0.6f)
        b("mrp",         7f,    720,  6400,  Role.CONTROL,   0.5f)
        b("knockout",    6f,    720,  8000,  Role.DMG,       0.9f)
        b("shade",       5.5f,  720,  7000,  Role.ASSASSIN,  0.8f)
    }

    /** Паспорт по названию класса модели (регистр не важен) или null, если это не бравлер. */
    fun get(name: String): BInfo? {
        val k = name.lowercase()
        return db[alias[k] ?: k]
    }

    /** Дальность атаки бравлера в долях высоты кадра (мерка - дальность самого Кольта). */
    fun rangeH(b: BInfo): Double = b.rng.toDouble() * Layout.SHOOT / COLT_RANGE_TILES

    /** Сколько «магазинов» Кольта нужно, чтобы убить бравлера с долей хп hpFrac (грубо: 70% пуль попадает). */
    fun slotsToKill(b: BInfo, hpFrac: Double): Double = b.hp * hpFrac.coerceIn(0.05, 1.0) / 2100.0
}

package com.oleg.coltbot

import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.random.Random

/** Параметры тактики, которые бот подстраивает сам по итогам матчей. */
class Params {
    var bandBase = 0.42     // желаемая дистанция боя (доли высоты экрана)
    var strafeMul = 1.0     // множитель бокового манёвра
    var aggrMul = 1.0       // множитель агрессивности
    var holdProb = 0.65     // вероятность стоять на месте, пока цель в зоне
    var standMs = 2500.0    // сколько стоим и бьём, когда от врага не убежать
}

/**
 * Самонастройка между матчами (простейшая эволюция, без нейросети и без внешних библиотек).
 * Половину матчей бот играет "лучшей известной" настройкой, другую половину - слегка изменённой копией.
 * Если копия набирает заметно больше награды, чем лучшая, она становится новой лучшей.
 * Награда считается в Brain.finishMatch: урон по врагам, оценка убийств, выживание, минус полученный урон.
 * Данные шумные, поэтому осмысленный эффект появляется после десятков матчей.
 */
class Tuner(private val sp: SharedPreferences) {
    private val names = arrayOf("bandBase", "strafeMul", "aggrMul", "holdProb", "standMs")
    private val lo = doubleArrayOf(0.30, 0.4, 0.6, 0.3, 1500.0)
    private val hi = doubleArrayOf(0.58, 1.8, 1.4, 0.9, 4000.0)
    private val def = doubleArrayOf(0.42, 1.0, 1.0, 0.65, 2500.0)
    private val best = DoubleArray(5)
    private val cand = DoubleArray(5)
    private var baseAvg = 0.0
    private var baseN = 0
    private var matches = 0
    private var adopted = 0
    private var testing = false
    val current = Params()
    private val rnd = java.util.Random()

    init {
        for (i in 0..4) best[i] = sp.getFloat(names[i], def[i].toFloat()).toDouble().coerceIn(lo[i], hi[i])
        baseAvg = sp.getFloat("baseAvg", 0f).toDouble()
        baseN = sp.getInt("baseN", 0)
        matches = sp.getInt("matches", 0)
        adopted = sp.getInt("adopted", 0)
        next()
    }

    private fun apply(v: DoubleArray) {
        current.bandBase = v[0]; current.strafeMul = v[1]; current.aggrMul = v[2]
        current.holdProb = v[3]; current.standMs = v[4]
    }

    private fun next() {
        testing = baseN >= 3 && Random.nextFloat() < 0.5f
        if (testing) {
            for (i in 0..4) cand[i] = (best[i] + rnd.nextGaussian() * 0.08 * (hi[i] - lo[i])).coerceIn(lo[i], hi[i])
            apply(cand)
        } else apply(best)
    }

    /** Вызывается в конце матча. Возвращает короткое пояснение для экрана. */
    fun report(reward: Double): String {
        matches++
        val note: String
        if (testing) {
            if (reward > baseAvg + 0.15 * abs(baseAvg) + 0.1) {
                for (i in 0..4) best[i] = cand[i]
                adopted++
                baseAvg = 0.5 * (baseAvg + reward)
                note = "пробный вариант принят"
            } else note = "пробный вариант не лучше"
        } else {
            baseAvg = if (baseN == 0) reward else 0.7 * baseAvg + 0.3 * reward
            baseN++
            note = "лучшая настройка"
        }
        val e = sp.edit()
        for (i in 0..4) e.putFloat(names[i], best[i].toFloat())
        e.putFloat("baseAvg", baseAvg.toFloat()).putInt("baseN", baseN).putInt("matches", matches).putInt("adopted", adopted).apply()
        next()
        return note
    }

    fun summary(): String =
        "матчей $matches, принято улучшений $adopted, средняя награда " + "%.2f".format(baseAvg) +
            "; сейчас " + (if (testing) "пробный вариант" else "лучшая настройка") +
            " (дист " + "%.2f".format(current.bandBase) + ", агрессия x" + "%.2f".format(current.aggrMul) + ")"
}

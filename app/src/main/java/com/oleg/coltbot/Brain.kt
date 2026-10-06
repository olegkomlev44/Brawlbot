package com.oleg.coltbot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class Brain {

    // --- Настройки и константы (нужно калибровать под ваше разрешение экрана!) ---
    private val OPTIMAL_DISTANCE = 450f  // Идеальная дистанция для Кольта
    private val MIN_SAFE_DISTANCE = 250f // Опасная зона
    private val BULLET_SPEED = 1800f     // Скорость пули (пикселей/сек)
    
    // --- Память бота (Predictive Aiming) ---
    private var lastEnemyX = -1f
    private var lastEnemyY = -1f
    private var lastFrameTime = 0L

    /**
     * Главный метод принятия решений. Вызывайте его каждый кадр.
     * @param frame текущий скриншот/кадр игры
     * @param playerX координаты вашего персонажа
     * @param playerY координаты вашего персонажа
     * @param enemyX координаты врага (если найден, иначе -1)
     * @param enemyY координаты врага
     * @return Pair: Вектор движения (x, y) и Вектор стрельбы (x, y). Значения от -1.0 до 1.0.
     */
    fun processFrame(
        frame: Bitmap, 
        playerX: Float, 
        playerY: Float, 
        enemyX: Float, 
        enemyY: Float
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        
        var moveVector = Pair(0f, 0f)
        var aimVector = Pair(0f, 0f)

        // 1. Проверяем состояние здоровья (Пункт 4)
        val healthPercent = checkHealth(frame, playerX, playerY)
        val isLowHealth = healthPercent < 0.4f // Меньше 40% ХП - отступаем

        if (enemyX != -1f && enemyY != -1f) {
            val distanceToEnemy = hypot((enemyX - playerX).toDouble(), (enemyY - playerY).toDouble()).toFloat()

            // 2. Рассчитываем движение (Пункт 1 + Учет ХП)
            moveVector = calculateMovement(playerX, playerY, enemyX, enemyY, distanceToEnemy, isLowHealth)

            // 3. Проверяем линию огня на наличие стен (Пункт 3)
            val isLosClear = isLineOfSightClear(frame, playerX, playerY, enemyX, enemyY)

            // 4. Принимаем решение о стрельбе
            if (isLosClear && !isLowHealth) {
                // Стена не мешает, ХП в норме -> Стреляем с упреждением (Пункт 2)
                aimVector = calculateAim(playerX, playerY, enemyX, enemyY)
            } else {
                // Стена мешает или мы при смерти -> Не стреляем, экономим патроны
                aimVector = Pair(0f, 0f)
                
                // Сбрасываем память упреждения, так как цель потеряна/недоступна
                lastEnemyX = -1f 
                lastEnemyY = -1f
            }
        } else {
            // Врагов нет - сбрасываем память
            lastEnemyX = -1f
            lastEnemyY = -1f
            // Здесь можно добавить логику патрулирования или сбора банок
        }

        return Pair(moveVector, aimVector)
    }

    // ==========================================
    // ЛОГИКА ДВИЖЕНИЯ (Кайт, Стейф, Отступление)
    // ==========================================
    private fun calculateMovement(
        pX: Float, pY: Float, 
        eX: Float, eY: Float, 
        distance: Float, 
        isPanicMode: Boolean
    ): Pair<Float, Float> {
        val dx = eX - pX
        val dy = eY - pY
        var mX = 0f
        var mY = 0f

        when {
            isPanicMode || distance < MIN_SAFE_DISTANCE -> {
                // Паника (мало ХП) или враг слишком близко — идем строго ОТ него
                mX = -dx
                mY = -dy
            }
            distance in MIN_SAFE_DISTANCE..OPTIMAL_DISTANCE -> {
                // Идеальная дистанция — делаем стрейф (мансы влево/вправо)
                val strafeDirection = if ((System.currentTimeMillis() / 1000) % 2 == 0L) 1f else -1f
                mX = -dy * strafeDirection
                mY = dx * strafeDirection
            }
            else -> {
                // Враг далеко, ХП в норме — сближаемся
                mX = dx
                mY = dy
            }
        }
        return normalizeVector(mX, mY)
    }

    // ==========================================
    // ЛОГИКА СТРЕЛЬБЫ (Упреждение)
    // ==========================================
    private fun calculateAim(pX: Float, pY: Float, eX: Float, eY: Float): Pair<Float, Float> {
        val currentTime = System.currentTimeMillis()
        var targetX = eX
        var targetY = eY

        if (lastEnemyX != -1f && lastEnemyY != -1f && lastFrameTime > 0) {
            val deltaTime = (currentTime - lastFrameTime) / 1000f 
            if (deltaTime in 0.01f..0.5f) { 
                val velX = (eX - lastEnemyX) / deltaTime
                val velY = (eY - lastEnemyY) / deltaTime
                val timeToHit = hypot((eX - pX).toDouble(), (eY - pY).toDouble()).toFloat() / BULLET_SPEED

                targetX = eX + (velX * timeToHit)
                targetY = eY + (velY * timeToHit)
            }
        }

        lastEnemyX = eX
        lastEnemyY = eY
        lastFrameTime = currentTime

        return normalizeVector(targetX - pX, targetY - pY)
    }

    // ==========================================
    // ЗДОРОВЬЕ (Сканирование пикселей)
    // ==========================================
    private fun checkHealth(frame: Bitmap, playerX: Float, playerY: Float): Float {
        // Координаты полоски ХП относительно центра игрока. 
        // ВАЖНО: Подберите эти значения (offset) под ваш экран!
        val barOffsetY = -150 
        val barWidth = 100
        val barHeight = 10

        val startX = (playerX - barWidth / 2).toInt().coerceIn(0, frame.width - 1)
        val startY = (playerY + barOffsetY).toInt().coerceIn(0, frame.height - barHeight - 1)

        var greenPixels = 0
        val totalPixels = barWidth * barHeight

        for (x in startX until startX + barWidth) {
            for (y in startY until startY + barHeight) {
                // Внимание: проверка выхода за границы
                if (x >= frame.width || y >= frame.height) continue
                
                val pixel = frame.getPixel(x, y)
                if (isHealthColor(pixel)) {
                    greenPixels++
                }
            }
        }
        return greenPixels.toFloat() / totalPixels
    }

    private fun isHealthColor(color: Int): Boolean {
        // Ищем ярко-зеленый цвет ХП. (Нужна калибровка RGB!)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return g > 150 && r < 100 && b < 100
    }

    // ==========================================
    // ПРЕПЯТСТВИЯ (Raycasting по алгоритму Брезенхема)
    // ==========================================
    private fun isLineOfSightClear(frame: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        var x = x0.toInt()
        var y = y0.toInt()
        val endX = x1.toInt()
        val endY = y1.toInt()

        val dx = abs(endX - x)
        val dy = abs(endY - y)
        val sx = if (x < endX) 1 else -1
        val sy = if (y < endY) 1 else -1
        var err = dx - dy

        // Идем по пикселям от игрока к врагу с шагом (можно брать каждый 3-й пиксель для скорости)
        while (true) {
            if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) break
            
            val pixel = frame.getPixel(x, y)
            if (isWallColor(pixel)) {
                return false // Наткнулись на стену
            }

            if (x == endX && y == endY) break
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x += sx
            }
            if (e2 < dx) {
                err += dx
                y += sy
            }
        }
        return true
    }

    private fun isWallColor(color: Int): Boolean {
        // Заглушка. Вам нужно замерить цвет стен (или их теней/контуров)
        // Либо, наоборот, проверять, что цвет НЕ является цветом земли.
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        
        // Пример (очень условный): если пиксель слишком темный, считаем препятствием
        return r < 50 && g < 50 && b < 50 
    }

    // ==========================================
    // УТИЛИТЫ
    // ==========================================
    private fun normalizeVector(x: Float, y: Float): Pair<Float, Float> {
        val length = hypot(x.toDouble(), y.toDouble()).toFloat()
        return if (length > 0) Pair(x / length, y / length) else Pair(0f, 0f)
    }
}

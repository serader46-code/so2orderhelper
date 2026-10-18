package com.so2.orderhelper

import java.util.Locale

object Analyzer {
    private fun f(v: Double) = String.format(Locale.US, "%.2f", v)

    /** orders / lots: цены сверху вниз. Возвращает (выгодно?, текст). */
    fun analyze(orders: List<Double>, lots: List<Double>, fee: Double): Pair<Boolean, String> {
        if (orders.isEmpty() || lots.isEmpty())
            return false to "Не вижу цен (заказы: ${orders.size}, лоты: ${lots.size}).\n" +
                "Откройте страницу предмета со списками или смените сторону в настройках."
        val bid = Math.round((orders[0] + 0.01) * 100) / 100.0   // топ-заказ + 0.01
        val l = lots.take(4)                                      // первый лот + следующие три
        val avg = l.average()
        val net = avg * (1 - fee / 100)                           // после комиссии
        val profit = net - bid
        val roi = profit / bid * 100
        val ok = profit > 0
        return ok to buildString {
            append("Топ заказ: ${f(orders[0])}  →  ваш: ${f(bid)}\n")
            append("Лоты (${l.size}): ${l.joinToString { f(it) }}\n")
            append("Среднее: ${f(avg)}, после −${f(fee)}%: ${f(net)}\n")
            append("Прибыль: ${f(profit)} (${f(roi)}%)\n")
            append(if (ok) "✅ ВЫГОДНО" else "❌ НЕ ВЫГОДНО")
            if (l.size < 4) append("\n⚠ найдено меньше 4 лотов")
        }
    }
}

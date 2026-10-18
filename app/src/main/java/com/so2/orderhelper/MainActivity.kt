package com.so2.orderhelper

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var fee: EditText
    private lateinit var right: CheckBox

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p) }
        val info = TextView(this).apply {
            text = "1) Нажмите «Запустить» и дайте разрешения.\n" +
                "2) Откройте в Standoff 2 страницу предмета (заказы и лоты).\n" +
                "3) Нажмите жёлтую кнопку ₲ — приложение прочитает цены и покажет вердикт.\n" +
                "Кнопку можно перетаскивать. Плашку с результатом закрывает тап по ней."
        }
        fee = EditText(this).apply {
            setText("20"); hint = "Комиссия при продаже, %"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        right = CheckBox(this).apply { text = "Заказы на ПРАВОЙ половине экрана (лоты слева)" }
        val start = Button(this).apply { text = "Запустить"; setOnClickListener { start() } }
        val stop = Button(this).apply {
            text = "Остановить"
            setOnClickListener { stopService(Intent(this@MainActivity, ScreenService::class.java)) }
        }
        listOf(info, TextView(this).apply { text = "\nКомиссия, %:" }, fee, right, start, stop)
            .forEach { root.addView(it) }
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Разрешите показ поверх других окон и нажмите «Запустить» снова", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 100 && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, ScreenService::class.java)
                    .putExtra("data", data)
                    .putExtra("fee", fee.text.toString().toDoubleOrNull() ?: 20.0)
                    .putExtra("right", right.isChecked)
            )
            moveTaskToBack(true)
        }
    }
}

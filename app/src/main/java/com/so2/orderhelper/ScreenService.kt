package com.so2.orderhelper

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var btn: TextView? = null
    private var panel: TextView? = null
    @Volatile private var want = false
    private var fee = 20.0
    private var ordersRight = false
    private var w = 0
    private var h = 0

    companion object { private val PRICE = Regex("""(\d+)[.,](\d{2})(?!\d)""") }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || projection != null) return START_NOT_STICKY
        @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val ch = "so2"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "SO2 helper", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("SO2 Order Helper работает")
            .setSmallIcon(android.R.drawable.ic_menu_search).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        fee = i.getDoubleExtra("fee", 20.0)
        ordersRight = i.getBooleanExtra("right", false)
        val dm = resources.displayMetrics
        w = dm.widthPixels; h = dm.heightPixels

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
        mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        projection = mp
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, ui)
        reader = r
        vd = mp.createVirtualDisplay("so2", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        showButton()
        return START_NOT_STICKY
    }

    private fun lp() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)

    private fun showButton() {
        val b = TextView(this).apply {
            text = "₲"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            setBackgroundColor(0xE6FFC107.toInt()); setPadding(36, 24, 36, 24)
        }
        val p = lp().apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 400 }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (Math.abs(dx) > 15 || Math.abs(dy) > 15) moved = true
                    if (moved) { p.x = ox + dx.toInt(); p.y = oy + dy.toInt(); wm.updateViewLayout(b, p) }
                }
                MotionEvent.ACTION_UP -> if (!moved) capture()
            }
            true
        }
        wm.addView(b, p); btn = b
    }

    // Убираем плашку (чтобы её цифры не попали в кадр), затем меняем кнопку — это вызывает новый кадр.
    private fun capture() {
        panel?.let { wm.removeView(it); panel = null }
        ui.postDelayed({ want = true; btn?.text = "…" }, 250)
    }

    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        if (!want) { img.close(); return }
        want = false
        val p = img.planes[0]
        val pad = p.rowStride - p.pixelStride * w
        val bmp = Bitmap.createBitmap(w + pad / p.pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(p.buffer)
        img.close()
        ocr.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { handle(it) }
            .addOnFailureListener { show("Ошибка OCR: ${it.message}", false) }
    }

    private fun handle(t: Text) {
        val orders = ArrayList<Pair<Int, Double>>()
        val lots = ArrayList<Pair<Int, Double>>()
        for (b in t.textBlocks) for (l in b.lines) {
            val r = l.boundingBox ?: continue
            if (r.centerY() < h * 0.15) continue            // верх экрана (баланс) игнорируем
            val toOrders = (r.centerX() < w / 2) != ordersRight
            for (m in PRICE.findAll(l.text)) {
                val v = "${m.groupValues[1]}.${m.groupValues[2]}".toDouble()
                (if (toOrders) orders else lots).add(r.centerY() to v)
            }
        }
        val res = Analyzer.analyze(
            orders.sortedBy { it.first }.map { it.second },
            lots.sortedBy { it.first }.map { it.second }, fee)
        show(res.second, res.first)
    }

    private fun show(msg: String, ok: Boolean) {
        ui.post {
            panel?.let { wm.removeView(it) }
            val tv = TextView(this).apply {
                text = msg; setTextColor(Color.WHITE); textSize = 15f; setPadding(32, 24, 32, 24)
                setBackgroundColor(if (ok) 0xF2006400.toInt() else 0xF2800000.toInt())
                setOnClickListener { wm.removeView(this); panel = null }
            }
            panel = tv
            wm.addView(tv, lp().apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 80 })
            btn?.text = "₲"
        }
    }

    override fun onDestroy() {
        btn?.let { wm.removeView(it) }
        panel?.let { wm.removeView(it) }
        vd?.release(); reader?.close(); projection?.stop(); ocr.close()
        super.onDestroy()
    }
}

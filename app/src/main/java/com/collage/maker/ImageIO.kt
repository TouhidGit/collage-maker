package com.collage.maker

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object ImageLoader {

    /** Decodes a photo, downsampled so its long side is at most [maxSide], with EXIF rotation applied. */
    fun load(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val longSide = max(bmp.width, bmp.height)
            if (longSide > maxSide) {
                val s = maxSide.toFloat() / longSide
                val scaled = Bitmap.createScaledBitmap(
                    bmp,
                    max(1, (bmp.width * s).roundToInt()),
                    max(1, (bmp.height * s).roundToInt()),
                    true
                )
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            rotate(bmp, orientation(cr, uri))
        } catch (e: Throwable) {
            null
        }
    }

    private fun orientation(cr: ContentResolver, uri: Uri): Int = try {
        cr.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun rotate(b: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return b
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r != b) b.recycle()
        return r
    }
}

object Exporter {

    /** Renders the collage at high resolution (long side = [longSide] px). */
    fun render(
        layout: PreparedLayout,
        images: List<Bitmap>,
        states: List<CellState>,
        bg: Int,
        longSide: Int = 3000
    ): Bitmap {
        val s = longSide / max(layout.w, layout.h)
        val bmp = Bitmap.createBitmap(
            max(1, (layout.w * s).roundToInt()),
            max(1, (layout.h * s).roundToInt()),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        canvas.scale(s, s)
        Renderer().draw(canvas, layout, images, states, bg)
        return bmp
    }

    fun saveToGallery(ctx: Context, bmp: Bitmap): Uri? {
        val name = "Collage_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CollageMaker")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val dir = File(pictures, "CollageMaker")
            dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            Uri.fromFile(file)
        }
    }

    fun shareUri(ctx: Context, bmp: Bitmap): Uri {
        val dir = File(ctx.cacheDir, "shared")
        dir.mkdirs()
        val file = File(dir, "collage.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    }
}

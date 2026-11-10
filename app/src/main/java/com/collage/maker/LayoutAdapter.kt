package com.collage.maker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

/** Small preview of a layout, drawn with placeholder colours. */
class LayoutThumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var collage: PreparedLayout? = null
        set(value) {
            field = value
            invalidate()
        }

    private val renderer = Renderer()

    override fun onDraw(canvas: Canvas) {
        val l = collage ?: return
        if (width == 0 || height == 0) return
        val s = min(width / l.w, height / l.h)
        val tx = (width - l.w * s) / 2f
        val ty = (height - l.h * s) / 2f
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        canvas.clipRect(0f, 0f, l.w, l.h)
        renderer.draw(canvas, l, emptyList(), null, Color.WHITE)
        canvas.restore()
    }
}

class LayoutAdapter(private val onPick: (LayoutDef) -> Unit) : RecyclerView.Adapter<LayoutAdapter.VH>() {

    private var items: List<LayoutDef> = emptyList()
    var selectedId: String = ""
    private var n = 4
    private var rw = 1
    private var rh = 1
    private var text = "LOVE"
    private val cache = HashMap<String, PreparedLayout>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: View = v.findViewById(R.id.thumbFrame)
        val thumb: LayoutThumbView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
    }

    fun setItems(list: List<LayoutDef>) {
        items = list
        notifyDataSetChanged()
    }

    fun setParams(n: Int, rw: Int, rh: Int, text: String) {
        if (n != this.n || rw != this.rw || rh != this.rh || text != this.text) {
            this.n = n
            this.rw = rw
            this.rh = rh
            this.text = text
            cache.clear()
            notifyDataSetChanged()
        }
    }

    fun select(id: String) {
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_layout, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val def = items[position]
        holder.name.text = def.name
        holder.frame.isSelected = def.id == selectedId
        holder.thumb.collage = cache.getOrPut(def.id) {
            val size = LayoutEngine.designSize(rw, rh)
            LayoutEngine.prepare(def, n, size.first, size.second, 36f, 0f, 3L, text)
        }
        holder.itemView.setOnClickListener { onPick(def) }
    }
}

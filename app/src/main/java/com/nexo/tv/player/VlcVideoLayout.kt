package com.nexo.tv.player

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.roundToInt
import org.videolan.libvlc.util.VLCVideoLayout as LibVlcVideoLayout

/**
 * Contenedor del VLCVideoLayout oficial, con modos de aspecto
 * (Pantalla completa, Zoom, 16:9, 4:3, Original).
 */
class VlcVideoLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Layout nativo de libVLC (Surface/Texture interno). */
    val vlcLayout = LibVlcVideoLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
    }

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var aspectMode: VlcEngine.AspectMode = VlcEngine.AspectMode.FILL

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(vlcLayout)
    }

    fun setVideoSize(width: Int, height: Int) {
        if (videoWidth != width || videoHeight != height) {
            videoWidth = width
            videoHeight = height
            post { requestLayout() }
        }
    }

    fun setAspectMode(mode: VlcEngine.AspectMode) {
        if (aspectMode != mode) {
            aspectMode = mode
            post { requestLayout() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
        val containerHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()

        var childWidth = containerWidth
        var childHeight = containerHeight

        when (aspectMode) {
            VlcEngine.AspectMode.FILL -> {
                childWidth = containerWidth
                childHeight = containerHeight
            }
            VlcEngine.AspectMode.ORIGINAL -> {
                if (containerAspect > videoAspect) {
                    childWidth = (containerHeight * videoAspect).roundToInt()
                    childHeight = containerHeight
                } else {
                    childWidth = containerWidth
                    childHeight = (containerWidth / videoAspect).roundToInt()
                }
            }
            VlcEngine.AspectMode.RATIO_16_9 -> {
                val targetAspect = 16f / 9f
                if (containerAspect > targetAspect) {
                    childWidth = (containerHeight * targetAspect).roundToInt()
                    childHeight = containerHeight
                } else {
                    childWidth = containerWidth
                    childHeight = (containerWidth / targetAspect).roundToInt()
                }
            }
            VlcEngine.AspectMode.RATIO_4_3 -> {
                val targetAspect = 4f / 3f
                if (containerAspect > targetAspect) {
                    childWidth = (containerHeight * targetAspect).roundToInt()
                    childHeight = containerHeight
                } else {
                    childWidth = containerWidth
                    childHeight = (containerWidth / targetAspect).roundToInt()
                }
            }
            VlcEngine.AspectMode.ZOOM -> {
                if (containerAspect > videoAspect) {
                    childWidth = containerWidth
                    childHeight = (containerWidth / videoAspect).roundToInt()
                } else {
                    childWidth = (containerHeight * videoAspect).roundToInt()
                    childHeight = containerHeight
                }
            }
        }

        val childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        vlcLayout.measure(childWidthSpec, childHeightSpec)
        setMeasuredDimension(containerWidth, containerHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val childW = vlcLayout.measuredWidth
        val childH = vlcLayout.measuredHeight
        val l = (width - childW) / 2
        val t = (height - childH) / 2
        vlcLayout.layout(l, t, l + childW, t + childH)
    }
}

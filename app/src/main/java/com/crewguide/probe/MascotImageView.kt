package com.crewguide.probe

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.min

/** 파츠 → res/drawable 번호. MascotPart.resName과 같은 이름이어야 한다(MascotRecipeTest가 파일 존재를 확인) */
object MascotRes {
    fun idOf(part: MascotPart): Int = when (part) {
        MascotPart.DECOR -> R.drawable.mascot_decor
        MascotPart.PROP_BACK -> R.drawable.mascot_prop_back
        MascotPart.SKIN_LIMBS -> R.drawable.mascot_skin_limbs
        MascotPart.LEGS_GAP -> R.drawable.mascot_legs_gap
        MascotPart.PROP_FRONT -> R.drawable.mascot_prop_front
        MascotPart.SHOES -> R.drawable.mascot_shoes
        MascotPart.OUTFIT -> R.drawable.mascot_outfit
        MascotPart.OUTFIT_SHADE -> R.drawable.mascot_outfit_shade
        MascotPart.SHIRT -> R.drawable.mascot_shirt
        MascotPart.OUTFIT_FRONT -> R.drawable.mascot_outfit_front
        MascotPart.BELT -> R.drawable.mascot_belt
        MascotPart.NAME_TAG -> R.drawable.mascot_name_tag
        MascotPart.BUTTONS -> R.drawable.mascot_buttons
        MascotPart.SKIN_HEAD -> R.drawable.mascot_skin_head
        MascotPart.HAIR -> R.drawable.mascot_hair
        MascotPart.HAT_MAIN -> R.drawable.mascot_hat_main
        MascotPart.HAT_TRIM -> R.drawable.mascot_hat_trim
        MascotPart.FACE -> R.drawable.mascot_face
        MascotPart.SCARF -> R.drawable.mascot_scarf
        MascotPart.SCARF_SHADE -> R.drawable.mascot_scarf_shade
        MascotPart.SCARF_DETAIL -> R.drawable.mascot_scarf_detail
        MascotPart.ACC_GLASSES -> R.drawable.mascot_acc_glasses
        MascotPart.ACC_RIBBON -> R.drawable.mascot_acc_ribbon
        MascotPart.ARM_SLEEVE -> R.drawable.mascot_arm_sleeve
        MascotPart.ARM_HAND -> R.drawable.mascot_arm_hand
        MascotPart.ARM_HAND_SHADE -> R.drawable.mascot_arm_hand_shade
    }
}

/** 파츠 벡터를 쌓아 보여 주는 이미지 뷰 한 장. [arm]이 true이면 팔 파츠만, 아니면 몸 파츠만 쌓는다. 같은 구성이면 다시 만들지 않는다 */
class MascotImageView(context: Context, private val arm: Boolean = false) : ImageView(context) {
    private var lastLayers: List<MascotLayer>? = null

    init { scaleType = ScaleType.FIT_CENTER }

    fun setStyle(style: CharacterStyle) {
        val layers = MascotRecipe.layers(style).filter { it.part.arm == arm }
        if (layers == lastLayers) return
        lastLayers = layers
        val drawables = layers.map { l ->
            val d: Drawable = context.resources.getDrawable(MascotRes.idOf(l.part), context.theme).mutate()
            l.tintRgb?.let { d.setTint(Palette.argb(it)) }   // 한 색 파츠만 tint(그림 원래 색이면 그대로)
            d
        }
        setImageDrawable(LayerDrawable(drawables.toTypedArray()))
    }
}

/**
 * 마스코트 전체: 몸 이미지 위에 같은 크기의 팔 이미지를 겹친다. 팔은 어깨(MascotWave.PIVOT_*)를 기준으로 회전해 손을 흔든다.
 * 몸·팔은 같은 [setStyle]을 받는다(꾸미기 상태가 바뀌면 둘 다 바로 다시 쌓는다).
 * 패딩은 이 뷰에 준다(자식은 안쪽 영역을 채운다).
 */
class MascotView(context: Context) : FrameLayout(context) {
    private val body = MascotImageView(context, arm = false)
    private val armView = MascotImageView(context, arm = true)
    private var waveAnimator: ObjectAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(armView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setStyle(style: CharacterStyle) {
        body.setStyle(style)
        armView.setStyle(style)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 팔 뷰 안에서 그림은 FIT_CENTER로 한가운데 정사각형으로 그려진다: 어깨 위치를 그 정사각형 기준으로 계산한다
        val w = armView.width.toFloat()
        val h = armView.height.toFloat()
        val side = min(w, h)
        armView.pivotX = (w - side) / 2f + side * MascotWave.PIVOT_X_FRACTION
        armView.pivotY = (h - side) / 2f + side * MascotWave.PIVOT_Y_FRACTION
    }

    /**
     * 짧은 인사 1번([MascotWave.GREET_CYCLES] 주기)을 재생하고 팔을 0도로 멈춘다. 반복하지 않는다.
     * 이미 돌고 있거나 시스템 애니메이션이 꺼져 있으면 아무것도 하지 않는다. 시작했으면 true.
     * 인사 1회 제한·주행 제한 판단은 호출하는 쪽([HomeScreen])이 [MascotWave.shouldGreet]로 한다.
     */
    fun playGreeting(): Boolean {
        if (waveAnimator?.isRunning == true) return false
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        val ease = AccelerateDecelerateInterpolator()
        val frames = MascotWave.KEYS.map { (f, deg) -> Keyframe.ofFloat(f, deg).apply { interpolator = ease } }
        val holder = PropertyValuesHolder.ofKeyframe(View.ROTATION, *frames.toTypedArray())
        waveAnimator = ObjectAnimator.ofPropertyValuesHolder(armView, holder).apply {
            duration = MascotWave.PERIOD_MS
            repeatCount = MascotWave.GREET_CYCLES - 1
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()   // 구간 속도는 키프레임별 보간기가 맡는다
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { armView.rotation = 0f }
            })
            start()
        }
        return true
    }

    fun stopWave() {
        waveAnimator?.cancel()
        waveAnimator = null
        armView.rotation = 0f
    }

    override fun onDetachedFromWindow() {
        stopWave()   // 인사 도중 화면이 사라져도 애니메이터가 남지 않게
        super.onDetachedFromWindow()
    }
}

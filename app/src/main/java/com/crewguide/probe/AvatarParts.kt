package com.crewguide.probe

/** 3D 승무원 실루엣의 부위 목록(순수 로직, Android 클래스 없음). 3D 시험(단계 1b)용. y축 위쪽, 얼굴은 +z 쪽 */
enum class MeshKind { SPHERE, CYLINDER }

class AvatarPart(
    val name: String,
    val mesh: MeshKind,
    val tx: Float, val ty: Float, val tz: Float,
    val sx: Float, val sy: Float, val sz: Float,
    val color: Int,   // 0xRRGGBB
) {
    /** 각 축 반쪽 길이(구: 크기 그대로, 원기둥: x·z는 크기, y는 높이 1이므로 크기의 절반) */
    fun halfX(): Float = sx
    fun halfY(): Float = if (mesh == MeshKind.CYLINDER) sy * 0.5f else sy
    fun halfZ(): Float = sz
}

object AvatarParts {
    // 기존 기본 스타일 색을 상수로 복사(스타일 연결은 단계 2)
    const val SKIN = 0xF8D9B8
    const val BODY = 0x5E7FA8
    const val SCARF = 0x9ED3F0
    const val EYE = 0x2A2630
    const val CHEEK = 0xF4A8A0

    val ALL: List<AvatarPart> = listOf(
        // 머리: 중심 y 0.55, 반지름 0.38 → y 0.17~0.93
        AvatarPart("머리", MeshKind.SPHERE, 0f, 0.55f, 0f, 0.38f, 0.38f, 0.38f, SKIN),
        // 몸통: 중심 y -0.3, 높이 0.9, 반지름 0.45 → y -0.75~0.15
        AvatarPart("몸통", MeshKind.CYLINDER, 0f, -0.30f, 0f, 0.45f, 0.90f, 0.45f, BODY),
        // 스카프: 짧은 고리, 중심 y 0.16, 높이 0.16 → y 0.08~0.24. 머리 아래(0.17)와 몸통 위(0.15) 사이 틈을 덮는다
        AvatarPart("스카프", MeshKind.CYLINDER, 0f, 0.16f, 0f, 0.32f, 0.16f, 0.32f, SCARF),
        // 눈: 얼굴 앞(+z) 표면에서 살짝 튀어나오게
        AvatarPart("왼눈", MeshKind.SPHERE, -0.13f, 0.58f, 0.34f, 0.05f, 0.05f, 0.05f, EYE),
        AvatarPart("오른눈", MeshKind.SPHERE, 0.13f, 0.58f, 0.34f, 0.05f, 0.05f, 0.05f, EYE),
        // 볼 홍조: 납작한 구
        AvatarPart("왼볼", MeshKind.SPHERE, -0.20f, 0.47f, 0.30f, 0.07f, 0.045f, 0.03f, CHEEK),
        AvatarPart("오른볼", MeshKind.SPHERE, 0.20f, 0.47f, 0.30f, 0.07f, 0.045f, 0.03f, CHEEK),
    )
}

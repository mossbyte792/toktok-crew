package com.crewguide.probe

/**
 * 캐릭터 자세(순수 데이터). 렌더러가 무엇이든(SVG 뷰, 3D 등) 같은 값으로 지시한다.
 * eyesOpen 0..1, waveDeg·tiltDeg는 도(degree). 이번 단계의 SVG 렌더러는 mouth만 그리고 나머지는 저장만 한다.
 */
data class CharacterPose(
    val mouth: MouthShape = MouthShape.SMILE,
    val eyesOpen: Float = 1f,
    val waveDeg: Float = 0f,
    val tiltDeg: Float = 0f,
)

/**
 * 캐릭터 캔버스(0~100 정사각 좌표)에서 화면에 보일 구간(순수 데이터, Android 클래스 없음).
 * (x0, y0)은 구간의 왼쪽 위, size는 한 변. 구간이 캔버스 밖(음수·100 초과)까지 가도 된다(바깥은 빈 배경).
 * 뷰의 정사각(한 변 side)에 이 구간이 꽉 차게 그려진다: scale = side/size, 이동 = (-x0·scale, -y0·scale).
 */
data class CharacterViewport(val x0: Float, val y0: Float, val size: Float) {
    fun scaleFor(side: Float): Float = side / size
    fun offsetX(side: Float): Float = -x0 * scaleFor(side)
    fun offsetY(side: Float): Float = -y0 * scaleFor(side)

    companion object {
        /** 기본: 캔버스 전체(이전 그림과 같음) */
        val FULL = CharacterViewport(0f, 0f, 100f)

        // ---- 구도 프리셋(설계 제안, 구도 답이 오기 전에는 쓰지 않음). 얼굴 = 캔버스 y 18~62 ----
        /** 캔버스 바깥 여백까지 포함해 축소: 배율 0.83배, 얼굴은 정사각 위에서 15%~51.7%(높이 36.7%), 아래 약 48%에 몸·팔 */
        val BUST_WIDE = CharacterViewport(-10f, 0f, 120f)
        /** 약간 축소: 배율 0.91배, 얼굴 16.4%~56.4%(높이 40%), 아래 약 44%에 몸 */
        val BUST_TALL = CharacterViewport(-5f, 0f, 110f)
        /** 위쪽 5만 잘라 살짝 확대: 배율 1.05배, 얼굴 13.7%~60%(높이 46.3%), 아래 40%에 몸 */
        val BUST_MID = CharacterViewport(0f, 5f, 95f)
        /** 상반신 구도: 캔버스 x −15~115, y 5~135. 얼굴 10%~43.8%(높이 33.8%), 어깨선 45.4% 부근, 몸은 아래 끝(100%)까지 */
        val BUST_UPPER = CharacterViewport(-15f, 5f, 130f)
        /** 아이 카드용 얼굴 구도: 캔버스 x 20~80, y 8~68. 머리 y 18~62, 모자 윗부분 y 12.5, 어깨 윗부분 포함 */
        val FACE = CharacterViewport(20f, 8f, 60f)
    }
}

/** 캐릭터를 그리는 쪽의 교체 가능한 접점. 3D 등으로 바꿔도 StageScreen은 이 인터페이스만 쓴다. */
interface CharacterRenderer {
    val view: android.view.View
    /** 이름을 applyStyle로 둔 이유: SvgCharacterView의 var style setter(setStyle)와 JVM 시그니처가 겹치지 않게 하기 위해서 */
    fun applyStyle(style: CharacterStyle)
    fun setPose(pose: CharacterPose)
}

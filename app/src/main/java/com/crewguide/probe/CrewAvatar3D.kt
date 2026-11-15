package com.crewguide.probe

import kotlin.math.cos
import kotlin.math.sin

/**
 * 무대 중앙 3D 승무원 아바타 부위·자세 생성기(순수 로직, Android·GL 클래스 없음).
 * - 기존 AvatarParts(단계 1b 시험용)는 그대로 보존하고, 실제 무대(StageScreen)의 CharacterStyle·CharacterPose·Scene과 1:1 연동한다.
 * - 항공사 로고·날개 배지·상표 연상 요소를 쓰지 않는다(필박스 정모 + 단색 띠, 빈 이름표, 셔츠 깃, 스카프).
 * - 카메라 기준(FOV 34°, eye z=3.35, lookAt y=0.05)에서 얼굴이 StageBubblePlacement의 FACE 사각형 안에 들어오도록 좌표를 맞춘다.
 */
class CrewPart3D(
    val name: String,
    val mesh: MeshKind,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val sx: Float,
    val sy: Float,
    val sz: Float,
    val colorRgb: Int,
    /** 로컬 회전(도). 적용 순서: pivot 이동 → rz → ry → rx → 부위 위치 → 크기 */
    val rxDeg: Float = 0f,
    val ryDeg: Float = 0f,
    val rzDeg: Float = 0f,
    /** 그룹 회전 중심(목·어깨 등). groupTilt/groupYaw/groupRoll이 있을 때 사용한다 */
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
    val pivotZ: Float = 0f,
    val groupRxDeg: Float = 0f,
    val groupRyDeg: Float = 0f,
    val groupRzDeg: Float = 0f,
    /** 반사광 강도(0=무광 천/피부, 0.5~0.9=눈동자·금속 단추·버클) */
    val shininess: Float = 0.12f,
)

object CrewAvatar3D {
    // 카메라 상수(StageBubblePlacement BUST_UPPER 얼굴 영역 y 10%~43.9%, 가로 33.8%와 1:1 정렬)
    const val CAM_FOV_DEG = 34f
    const val CAM_EYE_Y = 0.10f
    const val CAM_EYE_Z = 3.35f
    const val CAM_CENTER_Y = 0.10f

    private const val NECK_PIVOT_Y = 0.21f
    private const val HEAD_Y = 0.52f
    private const val HEAD_RX = 0.285f
    private const val HEAD_RY = 0.295f
    private const val HEAD_RZ = 0.275f

    private const val WHITE_SHIRT = 0xF7F9FC
    private const val GOLD_BUTTON = 0xF2C14E
    private const val NAME_TAG_RGB = 0xFFE082
    private const val CHEEK_LIGHT = 0xF49E97
    private const val CHEEK_WARM = 0xE07A72
    private const val MOUTH_LINE = 0x3A2630
    private const val MOUTH_INNER = 0xD95B66
    private const val BELT_STRAP = 0x28354A
    private const val BELT_METAL = 0xDCE3EC
    private const val BELT_LATCH = 0xAEBAC8

    /** 장면·경과 시간·기본 자세로부터 3D 부위 목록을 생성한다(이름 중복 없음) */
    fun build(
        style: CharacterStyle = CharacterStyle.DEFAULT,
        pose: CharacterPose = CharacterPose(),
        scene: Scene = Scene.PICK,
        sceneElapsedMs: Long = 0L,
        absoluteMs: Long = 0L,
    ): List<CrewPart3D> {
        val s = CharacterOptions.sanitize(style)
        val uni = CharacterOptions.uniform(s)
        val skinRgb = CharacterOptions.SKIN[s.skinColorIndex].argb and 0xFFFFFF
        val hairRgb = CharacterOptions.HAIR[s.hairColorIndex].argb and 0xFFFFFF
        val outfit = CharacterOptions.OUTFIT[s.outfitColorIndex]
        val bodyRgb = outfit.bodyArgb and 0xFFFFFF
        val scarfRgb = outfit.scarfArgb and 0xFFFFFF
        val eyeRgb = CharacterOptions.EYE_INK_ARGB and 0xFFFFFF
        val noseRgb = darken(skinRgb, 0.91f)
        val cheekRgb = if (s.skinColorIndex >= 3) CHEEK_WARM else CHEEK_LIGHT

        val locked = scene == Scene.LOCKED
        val tSec = if (locked || absoluteMs < 0L) 0f else (absoluteMs % 60000L) / 1000f
        val scSec = if (locked || sceneElapsedMs < 0L) 0f else sceneElapsedMs / 1000f

        // 절제된 숨쉬기·고개 미세 움직임(입체감이 자연스럽게 느껴지도록 소폭 적용)
        val breathY = if (locked) 0f else (sin(tSec * 1.9) * 0.010).toFloat()
        val idleYaw = if (locked) 0f else (sin(tSec * 1.1) * 3.2).toFloat()
        val greetNod = when {
            locked -> 0f
            scene == Scene.READY && scSec < 1.2f -> (sin(scSec / 1.2 * Math.PI) * 6.5).toFloat()
            scene == Scene.ANNOUNCE -> (sin(scSec * 3.4) * 2.5).toFloat()
            else -> 0f
        }
        val headRx = greetNod
        val headRy = idleYaw + when (scene) {
            Scene.PICK -> -2.5f
            Scene.EXIT_CHECK -> 4.0f
            else -> 0f
        }
        val headRz = -pose.tiltDeg   // QUESTION일 때 살짝 기울여 경청

        val out = ArrayList<CrewPart3D>(48)

        fun headPart(
            name: String, mesh: MeshKind,
            tx: Float, ty: Float, tz: Float,
            sx: Float, sy: Float, sz: Float,
            color: Int,
            rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
            shininess: Float = 0.14f,
        ) {
            out.add(
                CrewPart3D(
                    name = name, mesh = mesh,
                    tx = tx, ty = ty + breathY, tz = tz,
                    sx = sx, sy = sy, sz = sz,
                    colorRgb = color,
                    rxDeg = rx, ryDeg = ry, rzDeg = rz,
                    pivotX = 0f, pivotY = NECK_PIVOT_Y + breathY, pivotZ = 0f,
                    groupRxDeg = headRx, groupRyDeg = headRy, groupRzDeg = headRz,
                    shininess = shininess,
                )
            )
        }

        // ---- 1. 머리·귀·머리카락 ----
        headPart("머리", MeshKind.SPHERE, 0f, HEAD_Y, 0f, HEAD_RX, HEAD_RY, HEAD_RZ, skinRgb, shininess = 0.18f)
        headPart("왼귀", MeshKind.SPHERE, -0.285f, HEAD_Y - 0.02f, -0.01f, 0.055f, 0.070f, 0.045f, skinRgb)
        headPart("오른귀", MeshKind.SPHERE, 0.285f, HEAD_Y - 0.02f, -0.01f, 0.055f, 0.070f, 0.045f, skinRgb)
        headPart("코", MeshKind.SPHERE, 0f, HEAD_Y - 0.035f, 0.278f, 0.030f, 0.026f, 0.028f, noseRgb, shininess = 0.20f)

        // 뒷머리·옆머리·앞머리(머리 모양 3종 분기)
        headPart("뒷머리", MeshKind.SPHERE, 0f, HEAD_Y + 0.03f, -0.045f, 0.308f, 0.312f, 0.285f, hairRgb, shininess = 0.25f)
        when (s.hairShapeIndex) {
            CharacterOptions.HAIR_SIDE_PART -> {
                headPart("앞머리왼", MeshKind.SPHERE, -0.11f, HEAD_Y + 0.17f, 0.155f, 0.16f, 0.095f, 0.12f, hairRgb, rz = 16f, shininess = 0.25f)
                headPart("앞머리오른", MeshKind.SPHERE, 0.09f, HEAD_Y + 0.155f, 0.165f, 0.20f, 0.105f, 0.12f, hairRgb, rz = -14f, shininess = 0.25f)
                headPart("옆머리왼", MeshKind.SPHERE, -0.255f, HEAD_Y - 0.03f, 0.03f, 0.068f, 0.16f, 0.11f, hairRgb, rz = 8f, shininess = 0.22f)
                headPart("옆머리오른", MeshKind.SPHERE, 0.262f, HEAD_Y - 0.06f, 0.03f, 0.072f, 0.20f, 0.11f, hairRgb, rz = -8f, shininess = 0.22f)
            }
            CharacterOptions.HAIR_TIED -> {
                headPart("앞머리둥근", MeshKind.SPHERE, 0f, HEAD_Y + 0.185f, 0.145f, 0.245f, 0.088f, 0.13f, hairRgb, shininess = 0.25f)
                headPart("묶음단", MeshKind.SPHERE, 0.23f, HEAD_Y + 0.11f, -0.17f, 0.115f, 0.115f, 0.115f, hairRgb, shininess = 0.28f)
                headPart("머리끈", MeshKind.CYLINDER, 0.175f, HEAD_Y + 0.09f, -0.13f, 0.065f, 0.035f, 0.065f, scarfRgb, rz = -35f, shininess = 0.30f)
            }
            else -> {
                headPart("앞머리둥근", MeshKind.SPHERE, 0f, HEAD_Y + 0.165f, 0.158f, 0.258f, 0.105f, 0.135f, hairRgb, shininess = 0.25f)
                headPart("옆머리왼", MeshKind.SPHERE, -0.255f, HEAD_Y - 0.05f, 0.02f, 0.070f, 0.18f, 0.12f, hairRgb, rz = 6f, shininess = 0.22f)
                headPart("옆머리오른", MeshKind.SPHERE, 0.255f, HEAD_Y - 0.05f, 0.02f, 0.070f, 0.18f, 0.12f, hairRgb, rz = -6f, shininess = 0.22f)
            }
        }

        // ---- 2. 눈·눈동자 하이라이트·눈썹·볼·입 ----
        val calmScale = if (s.toneIndex == CharacterOptions.TONE_CALM) 0.80f else 1.0f
        val eyeOpen = (pose.eyesOpen.coerceIn(0.1f, 1f) * calmScale).coerceIn(0.08f, 1f)
        val eyeSy = 0.044f * eyeOpen
        headPart("왼눈", MeshKind.SPHERE, -0.105f, HEAD_Y + 0.015f, 0.252f, 0.036f, eyeSy, 0.022f, eyeRgb, shininess = 0.75f)
        headPart("오른눈", MeshKind.SPHERE, 0.105f, HEAD_Y + 0.015f, 0.252f, 0.036f, eyeSy, 0.022f, eyeRgb, shininess = 0.75f)
        if (eyeOpen > 0.35f) {
            headPart("왼눈빛", MeshKind.SPHERE, -0.093f, HEAD_Y + 0.028f, 0.270f, 0.012f, 0.012f, 0.010f, WHITE_SHIRT, shininess = 0.9f)
            headPart("오른눈빛", MeshKind.SPHERE, 0.117f, HEAD_Y + 0.028f, 0.270f, 0.012f, 0.012f, 0.010f, WHITE_SHIRT, shininess = 0.9f)
        }
        val browTilt = if (scene == Scene.QUESTION) 10f else if (s.toneIndex == CharacterOptions.TONE_CALM) 2f else 6f
        headPart("왼눈썹", MeshKind.SPHERE, -0.108f, HEAD_Y + 0.088f, 0.246f, 0.050f, 0.012f, 0.014f, hairRgb, rz = browTilt)
        headPart("오른눈썹", MeshKind.SPHERE, 0.108f, HEAD_Y + 0.088f, 0.246f, 0.050f, 0.012f, 0.014f, hairRgb, rz = -browTilt)

        headPart("왼볼", MeshKind.SPHERE, -0.168f, HEAD_Y - 0.055f, 0.228f, 0.052f, 0.032f, 0.018f, cheekRgb, ry = -18f, shininess = 0.10f)
        headPart("오른볼", MeshKind.SPHERE, 0.168f, HEAD_Y - 0.055f, 0.228f, 0.052f, 0.032f, 0.018f, cheekRgb, ry = 18f, shininess = 0.10f)

        // 입 모양 4종
        val mouthY = HEAD_Y - 0.115f
        when (pose.mouth) {
            MouthShape.SMILE -> {
                headPart("입왼", MeshKind.SPHERE, -0.034f, mouthY + 0.007f, 0.262f, 0.028f, 0.011f, 0.012f, MOUTH_LINE, rz = -20f)
                headPart("입가운데", MeshKind.SPHERE, 0f, mouthY, 0.266f, 0.030f, 0.011f, 0.012f, MOUTH_LINE)
                headPart("입오른", MeshKind.SPHERE, 0.034f, mouthY + 0.007f, 0.262f, 0.028f, 0.011f, 0.012f, MOUTH_LINE, rz = 20f)
            }
            MouthShape.SMALL -> {
                headPart("입테두리", MeshKind.SPHERE, 0f, mouthY, 0.262f, 0.034f, 0.030f, 0.015f, MOUTH_LINE)
                headPart("입안", MeshKind.SPHERE, 0f, mouthY - 0.002f, 0.268f, 0.024f, 0.020f, 0.012f, MOUTH_INNER)
            }
            MouthShape.WIDE -> {
                headPart("입테두리", MeshKind.SPHERE, 0f, mouthY - 0.004f, 0.258f, 0.058f, 0.042f, 0.018f, MOUTH_LINE)
                headPart("입안", MeshKind.SPHERE, 0f, mouthY - 0.006f, 0.266f, 0.045f, 0.030f, 0.014f, MOUTH_INNER)
            }
            MouthShape.CLOSED -> {
                headPart("입가운데", MeshKind.SPHERE, 0f, mouthY, 0.265f, 0.042f, 0.010f, 0.012f, MOUTH_LINE)
            }
        }

        // ---- 3. 소품(둥근 안경 / 리본 핀) 및 승무원 필박스 정모(로고·배지 없음) ----
        when (s.accessoryIndex) {
            CharacterOptions.ACC_GLASSES -> {
                val frameRgb = 0x374151
                for ((sideTag, cx) in listOf("왼" to -0.105f, "오른" to 0.105f)) {
                    headPart("안경위$sideTag", MeshKind.SPHERE, cx, HEAD_Y + 0.068f, 0.268f, 0.052f, 0.008f, 0.010f, frameRgb, shininess = 0.55f)
                    headPart("안경아래$sideTag", MeshKind.SPHERE, cx, HEAD_Y - 0.038f, 0.268f, 0.052f, 0.008f, 0.010f, frameRgb, shininess = 0.55f)
                    headPart("안경좌$sideTag", MeshKind.SPHERE, cx - 0.054f, HEAD_Y + 0.015f, 0.264f, 0.008f, 0.048f, 0.010f, frameRgb, shininess = 0.55f)
                    headPart("안경우$sideTag", MeshKind.SPHERE, cx + 0.054f, HEAD_Y + 0.015f, 0.264f, 0.008f, 0.048f, 0.010f, frameRgb, shininess = 0.55f)
                }
                headPart("안경코다리", MeshKind.SPHERE, 0f, HEAD_Y + 0.025f, 0.272f, 0.042f, 0.008f, 0.010f, frameRgb, shininess = 0.55f)
            }
            CharacterOptions.ACC_RIBBON -> {
                headPart("리본왼", MeshKind.SPHERE, -0.225f, HEAD_Y + 0.22f, 0.17f, 0.052f, 0.032f, 0.024f, cheekRgb, rz = 22f, shininess = 0.30f)
                headPart("리본오른", MeshKind.SPHERE, -0.145f, HEAD_Y + 0.235f, 0.185f, 0.052f, 0.032f, 0.024f, cheekRgb, rz = -18f, shininess = 0.30f)
                headPart("리본매듭", MeshKind.SPHERE, -0.185f, HEAD_Y + 0.228f, 0.19f, 0.022f, 0.022f, 0.022f, scarfRgb, shininess = 0.35f)
            }
        }
        if (uni.hasHat) {
            // 챙 없는 승무원 필박스 정모 + 스카프 색 둘레 띠(앞머리 위로 선명히 보이게 배치, 항공사 상징·로고 없음)
            headPart("정모본체", MeshKind.CYLINDER, 0.015f, HEAD_Y + 0.305f, 0.035f, 0.222f, 0.135f, 0.218f, bodyRgb, rx = 4f, rz = -5f, shininess = 0.32f)
            headPart("정모윗면", MeshKind.SPHERE, 0.015f, HEAD_Y + 0.368f, 0.035f, 0.220f, 0.038f, 0.216f, bodyRgb, rx = 4f, rz = -5f, shininess = 0.32f)
            headPart("정모띠", MeshKind.CYLINDER, 0.014f, HEAD_Y + 0.252f, 0.042f, 0.230f, 0.034f, 0.226f, scarfRgb, rx = 4f, rz = -5f, shininess = 0.42f)
        }

        // ---- 4. 목·승무원 재킷 몸통·셔츠 깃·단추·스카프·빈 이름표 ----
        out.add(CrewPart3D("목", MeshKind.CYLINDER, 0f, 0.19f + breathY * 0.6f, 0f, 0.105f, 0.16f, 0.105f, skinRgb))
        // 재킷 몸통을 y -1.14까지 연장해 컨트롤 패널이 없는 장면(안내·잠금·하차 확인)에서도 하단 띠 위까지 끊김 없이 이어지게 한다
        out.add(CrewPart3D("몸통", MeshKind.CYLINDER, 0f, -0.52f + breathY * 0.4f, 0f, 0.34f, 1.24f, 0.25f, bodyRgb, shininess = 0.22f))
        out.add(CrewPart3D("어깨둥근", MeshKind.SPHERE, 0f, 0.06f + breathY * 0.5f, 0f, 0.355f, 0.145f, 0.245f, bodyRgb, shininess = 0.22f))
        out.add(CrewPart3D("셔츠앞", MeshKind.SPHERE, 0f, 0.03f + breathY * 0.5f, 0.224f, 0.092f, 0.115f, 0.046f, WHITE_SHIRT, shininess = 0.15f))
        out.add(CrewPart3D("왼깃", MeshKind.SPHERE, -0.086f, 0.072f + breathY * 0.5f, 0.242f, 0.070f, 0.038f, 0.032f, WHITE_SHIRT, rzDeg = -26f, shininess = 0.20f))
        out.add(CrewPart3D("오른깃", MeshKind.SPHERE, 0.086f, 0.072f + breathY * 0.5f, 0.242f, 0.070f, 0.038f, 0.032f, WHITE_SHIRT, rzDeg = 26f, shininess = 0.20f))
        out.add(CrewPart3D("윗단추", MeshKind.SPHERE, 0f, -0.22f + breathY * 0.4f, 0.254f, 0.026f, 0.026f, 0.020f, GOLD_BUTTON, shininess = 0.80f))
        out.add(CrewPart3D("가운데단추", MeshKind.SPHERE, 0f, -0.37f + breathY * 0.4f, 0.254f, 0.026f, 0.026f, 0.020f, GOLD_BUTTON, shininess = 0.80f))
        out.add(CrewPart3D("아랫단추", MeshKind.SPHERE, 0f, -0.52f + breathY * 0.4f, 0.254f, 0.026f, 0.026f, 0.020f, GOLD_BUTTON, shininess = 0.80f))

        if (uni.hasScarf) {
            out.add(CrewPart3D("스카프목", MeshKind.CYLINDER, 0f, 0.142f + breathY * 0.6f, 0.028f, 0.164f, 0.072f, 0.162f, scarfRgb, shininess = 0.34f))
            out.add(CrewPart3D("스카프매듭", MeshKind.SPHERE, 0.088f, 0.102f + breathY * 0.6f, 0.262f, 0.050f, 0.046f, 0.040f, scarfRgb, shininess = 0.36f))
            out.add(CrewPart3D("스카프왼", MeshKind.SPHERE, 0.068f, 0.012f + breathY * 0.5f, 0.268f, 0.040f, 0.098f, 0.026f, scarfRgb, rzDeg = -14f, shininess = 0.34f))
            out.add(CrewPart3D("스카프오른", MeshKind.SPHERE, 0.138f, 0.025f + breathY * 0.5f, 0.260f, 0.040f, 0.092f, 0.026f, scarfRgb, rzDeg = 26f, shininess = 0.34f))
        }
        if (uni.hasNameTag) {
            out.add(CrewPart3D("이름표", MeshKind.SPHERE, -0.155f, -0.13f + breathY * 0.4f, 0.238f, 0.068f, 0.036f, 0.018f, NAME_TAG_RGB, ryDeg = -16f, shininess = 0.65f))
        }

        // ---- 5. 양팔·손 및 장면별 승무원 브리핑 동작(벨트 시연 소품 / 경청 / 축하 손 흔들기) ----
        val shY = 0.02f + breathY * 0.5f
        when (scene) {
            Scene.ANNOUNCE -> {
                // 기내 브리핑 자세: 두 손을 가슴 앞으로 모아 벨트 버클 시연 소품을 양손에 들고 살짝 맞물려 보인다(착용 완료 단정 아님)
                val slide = (0.045f + 0.030f * sin(scSec * 3.0).toFloat()).coerceIn(0.015f, 0.085f)
                addBriefingArmsAndBelt(out, shY, slide, bodyRgb, skinRgb)
            }
            Scene.YES -> {
                // 보호자 '예' 응답 뒤 아이 안내: 왼팔은 단정히, 오른팔(화면 오른쪽)은 들어 밝게 손을 흔든다
                addRestArm(out, isRight = false, shY = shY, bodyRgb = bodyRgb, skinRgb = skinRgb)
                addWaveArm(out, shY = shY, waveDeg = pose.waveDeg, bodyRgb = bodyRgb, skinRgb = skinRgb)
            }
            Scene.QUESTION -> {
                // 보호자 응답 경청: 오른손을 가슴 옆으로 살짝 들어 귀 기울이는 안내 자세
                addRestArm(out, isRight = false, shY = shY, bodyRgb = bodyRgb, skinRgb = skinRgb)
                addInquireArm(out, shY = shY, bodyRgb = bodyRgb, skinRgb = skinRgb)
            }
            Scene.READY -> {
                // 시작 준비 완료: 탑승 환영 인사(처음 1.6초 부드러운 손인사 후 단정한 안내 자세)
                addRestArm(out, isRight = false, shY = shY, bodyRgb = bodyRgb, skinRgb = skinRgb)
                if (!locked && scSec < 1.6f) {
                    val welcomeWave = (sin(scSec * 5.0) * 12.0).toFloat()
                    addWaveArm(out, shY = shY, waveDeg = welcomeWave, bodyRgb = bodyRgb, skinRgb = skinRgb)
                } else {
                    addPoliteClaspedRightArm(out, shY = shY, bodyRgb = bodyRgb, skinRgb = skinRgb)
                }
            }
            else -> {
                // PICK, WAIT_GUARDIAN, TRIP_END, EXIT_CHECK, LOCKED: 단정한 승무원 기본 자세
                addRestArm(out, isRight = false, shY = shY, bodyRgb = bodyRgb, skinRgb = skinRgb)
                addPoliteClaspedRightArm(out, shY = shY, bodyRgb = bodyRgb, skinRgb = skinRgb)
            }
        }

        return out
    }

    private fun addRestArm(out: MutableList<CrewPart3D>, isRight: Boolean, shY: Float, bodyRgb: Int, skinRgb: Int) {
        val sign = if (isRight) 1f else -1f
        val side = if (isRight) "오른" else "왼"
        val sx = sign * 0.330f
        out.add(CrewPart3D("${side}어깨", MeshKind.SPHERE, sx, shY + 0.01f, 0.01f, 0.102f, 0.102f, 0.102f, bodyRgb, shininess = 0.22f))
        out.add(CrewPart3D("${side}팔윗", MeshKind.CYLINDER, sx + sign * 0.025f, shY - 0.18f, 0.03f, 0.078f, 0.35f, 0.078f, bodyRgb, rzDeg = -sign * 8f, rxDeg = -8f, shininess = 0.22f))
        out.add(CrewPart3D("${side}팔아랫", MeshKind.CYLINDER, sx - sign * 0.025f, shY - 0.41f, 0.13f, 0.070f, 0.28f, 0.070f, bodyRgb, rzDeg = sign * 26f, rxDeg = -28f, shininess = 0.22f))
        out.add(CrewPart3D("${side}손", MeshKind.SPHERE, sx - sign * 0.11f, shY - 0.52f, 0.22f, 0.066f, 0.062f, 0.055f, skinRgb, shininess = 0.18f))
    }

    private fun addPoliteClaspedRightArm(out: MutableList<CrewPart3D>, shY: Float, bodyRgb: Int, skinRgb: Int) {
        out.add(CrewPart3D("오른어깨", MeshKind.SPHERE, 0.330f, shY + 0.01f, 0.01f, 0.102f, 0.102f, 0.102f, bodyRgb, shininess = 0.22f))
        out.add(CrewPart3D("오른팔윗", MeshKind.CYLINDER, 0.355f, shY - 0.18f, 0.03f, 0.078f, 0.35f, 0.078f, bodyRgb, rzDeg = -8f, rxDeg = -8f, shininess = 0.22f))
        out.add(CrewPart3D("오른팔아랫", MeshKind.CYLINDER, 0.295f, shY - 0.40f, 0.14f, 0.070f, 0.28f, 0.070f, bodyRgb, rzDeg = -30f, rxDeg = -30f, shininess = 0.22f))
        out.add(CrewPart3D("오른손", MeshKind.SPHERE, 0.195f, shY - 0.50f, 0.24f, 0.066f, 0.062f, 0.055f, skinRgb, shininess = 0.18f))
    }

    private fun addInquireArm(out: MutableList<CrewPart3D>, shY: Float, bodyRgb: Int, skinRgb: Int) {
        out.add(CrewPart3D("오른어깨", MeshKind.SPHERE, 0.330f, shY + 0.01f, 0.02f, 0.102f, 0.102f, 0.102f, bodyRgb, shininess = 0.22f))
        out.add(CrewPart3D("오른팔윗", MeshKind.CYLINDER, 0.37f, shY - 0.14f, 0.06f, 0.078f, 0.30f, 0.078f, bodyRgb, rzDeg = 14f, rxDeg = -18f, shininess = 0.22f))
        out.add(CrewPart3D("오른팔아랫", MeshKind.CYLINDER, 0.35f, shY - 0.08f, 0.22f, 0.070f, 0.26f, 0.070f, bodyRgb, rzDeg = -22f, rxDeg = -65f, shininess = 0.22f))
        out.add(CrewPart3D("오른손", MeshKind.SPHERE, 0.295f, shY + 0.02f, 0.31f, 0.068f, 0.072f, 0.052f, skinRgb, rzDeg = -12f, shininess = 0.18f))
    }

    private fun addWaveArm(out: MutableList<CrewPart3D>, shY: Float, waveDeg: Float, bodyRgb: Int, skinRgb: Int) {
        // 어깨(0.330, shY, 0.03)를 축으로 팔 전체가 손 흔들기 각도(waveDeg)만큼 부드럽게 회전
        val px = 0.330f; val pz = 0.03f
        val roll = -waveDeg * 0.9f
        out.add(CrewPart3D("오른어깨", MeshKind.SPHERE, px, shY + 0.01f, pz, 0.102f, 0.102f, 0.102f, bodyRgb, shininess = 0.22f))
        out.add(
            CrewPart3D(
                "오른팔윗", MeshKind.CYLINDER, px + 0.13f, shY + 0.03f, pz + 0.06f,
                0.074f, 0.28f, 0.074f, bodyRgb, rzDeg = -68f, rxDeg = -16f,
                pivotX = px, pivotY = shY, pivotZ = pz, groupRzDeg = roll, shininess = 0.22f,
            )
        )
        out.add(
            CrewPart3D(
                "오른팔아랫", MeshKind.CYLINDER, px + 0.25f, shY + 0.20f, pz + 0.14f,
                0.066f, 0.26f, 0.066f, bodyRgb, rzDeg = -16f, rxDeg = -14f,
                pivotX = px, pivotY = shY, pivotZ = pz, groupRzDeg = roll, shininess = 0.22f,
            )
        )
        out.add(
            CrewPart3D(
                "오른손", MeshKind.SPHERE, px + 0.29f, shY + 0.35f, pz + 0.18f,
                0.070f, 0.078f, 0.052f, skinRgb, rzDeg = -12f,
                pivotX = px, pivotY = shY, pivotZ = pz, groupRzDeg = roll, shininess = 0.18f,
            )
        )
    }

    private fun addBriefingArmsAndBelt(out: MutableList<CrewPart3D>, shY: Float, slide: Float, bodyRgb: Int, skinRgb: Int) {
        val beltY = shY - 0.24f
        val beltZ = 0.34f
        // 양팔을 가슴 앞으로 올려 벨트 양끝을 잡은 모습
        for ((isRight, side) in listOf(false to "왼", true to "오른")) {
            val sign = if (isRight) 1f else -1f
            val handX = sign * (slide + 0.12f)
            out.add(CrewPart3D("${side}어깨", MeshKind.SPHERE, sign * 0.365f, shY, 0.02f, 0.088f, 0.088f, 0.088f, bodyRgb, shininess = 0.22f))
            out.add(CrewPart3D("${side}팔윗", MeshKind.CYLINDER, sign * 0.33f, shY - 0.13f, 0.10f, 0.074f, 0.28f, 0.074f, bodyRgb, rzDeg = sign * 18f, rxDeg = -32f, shininess = 0.22f))
            out.add(CrewPart3D("${side}팔아랫", MeshKind.CYLINDER, sign * 0.23f, beltY + 0.02f, 0.23f, 0.066f, 0.24f, 0.066f, bodyRgb, rzDeg = sign * 48f, rxDeg = -52f, shininess = 0.22f))
            out.add(CrewPart3D("${side}손", MeshKind.SPHERE, handX, beltY, beltZ - 0.01f, 0.062f, 0.060f, 0.054f, skinRgb, shininess = 0.18f))
        }
        // 벨트 스트랩과 금속 버클 양쪽(가운데에서 맞물리는 시연 소품)
        out.add(CrewPart3D("벨트띠왼", MeshKind.SPHERE, -(slide + 0.15f), beltY, beltZ, 0.095f, 0.028f, 0.016f, BELT_STRAP, shininess = 0.20f))
        out.add(CrewPart3D("벨트띠오른", MeshKind.SPHERE, (slide + 0.15f), beltY, beltZ, 0.095f, 0.028f, 0.016f, BELT_STRAP, shininess = 0.20f))
        out.add(CrewPart3D("벨트버클왼", MeshKind.SPHERE, -(slide + 0.042f), beltY, beltZ + 0.012f, 0.045f, 0.042f, 0.022f, BELT_METAL, shininess = 0.85f))
        out.add(CrewPart3D("벨트걸쇠오른", MeshKind.SPHERE, (slide + 0.035f), beltY, beltZ + 0.012f, 0.038f, 0.030f, 0.018f, BELT_LATCH, shininess = 0.85f))
    }

    private fun darken(rgb: Int, factor: Float): Int {
        val r = (((rgb shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((rgb shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((rgb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /** 코사인 보조(테스트용 각도 검증에서도 사용 가능) */
    fun degToRad(deg: Float): Double = deg * Math.PI / 180.0
    fun cosDeg(deg: Float): Float = cos(degToRad(deg)).toFloat()
}

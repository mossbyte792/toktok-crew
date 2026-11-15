import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 2026-11-10 2차 디렉티브 ①: 공모전 제출용 로컬 서명. local.properties(커밋 제외)의 toktok.signing이 가리키는
// 서명 설정 파일(storeFile·keyAlias·passwordFile, 저장소 추적 제외 경로)에서만 읽는다. 비밀번호를 이 파일에 쓰지 않는다.
// 설정이 없으면 release는 지금처럼 서명 없이 만들어진다(다른 PC에서도 빌드가 깨지지 않게).
val contestSigning: Properties? = run {
    val local = rootProject.file("local.properties")
    if (!local.isFile) return@run null
    val lp = Properties().apply { local.reader(Charsets.UTF_8).use { load(it) } }
    val path = lp.getProperty("toktok.signing") ?: return@run null
    val f = file(path)
    if (!f.isFile) return@run null
    Properties().apply { f.reader(Charsets.UTF_8).use { load(it) } }
}

android {
    namespace = "com.crewguide.probe"
    compileSdk = 35
    // 2026-11-07 Unit 5.1: 플랫폼 주행 제한(CarUxRestrictionsManager) 연동용. 런타임 라이브러리는 Manifest uses-library(required=false)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.crewguide.probe"
        minSdk = 31          // 에뮬레이터의 Pleos 차량 서비스가 minSdk 31
        targetSdk = 34
        versionCode = 6   // 2026-11-12 V6 디자인 개발 빌드(RC1 vc4 위 업데이트 시험용, 마켓 배포·승인 Release 교체 아님)
        versionName = "0.6.1-design-v6-dev"   // 2026-11-12 V6 C안(Hybrid) 홈 적용, 기능 기준 324f753 유지
    }
    if (contestSigning != null) {
        signingConfigs {
            create("contest") {
                val pw = file(contestSigning.getProperty("passwordFile")).readText(Charsets.UTF_8).trim()
                storeFile = file(contestSigning.getProperty("storeFile"))
                storeType = "PKCS12"
                storePassword = pw
                keyAlias = contestSigning.getProperty("keyAlias")
                keyPassword = pw
            }
        }
    }
    // 2026-11-08 v6.1: 공식 캐릭터 PNG(res/drawable-nodpi/crew_v61_*)를 APK 안에서도 원본 바이트 그대로 둔다(재압축 금지)
    buildTypes {
        getByName("release") {
            isCrunchPngs = false
            if (contestSigning != null) signingConfig = signingConfigs.getByName("contest")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}

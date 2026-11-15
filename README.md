# 톡톡크루 (TOK TOK CREW)

보호자 혼자 아이를 뒷좌석에 태우고 운전할 때, 차량의 착석·벨트·기어·문·내비 신호에 맞춰 승무원 캐릭터가 출발 전 확인부터 이동 안내, 하차 확인까지 대신 말해 주는 Pleos 헤드유닛 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## APK
- 위치: `apk/톡톡크루_release.apk` (서명된 release 빌드)
- SHA-256: `d5dcd586eef38293730e9fe1862179a3a59e542c86c71619b65e1a0d9d747c43`
- 패키지: `com.crewguide.probe`, minSdk 31 / targetSdk 34

## 테스트 환경
| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34) |
| 화면 규격 | 2560×1440, 160dpi, 가로 |
| 차량 신호 조작 | 에뮬레이터의 Virtual Console (`http://127.0.0.1:8088/`, `adb forward tcp:8088 tcp:8088`) |
| 사용 SDK | Pleos Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1 |

실차에서는 확인하지 않았습니다.

## 실행 방법
1. Pleos Connect 에뮬레이터 v2.1.0을 켭니다.
2. APK를 설치하고 실행합니다(에뮬레이터의 앱 사용자는 10번입니다).
   ```
   adb install --user 10 apk/톡톡크루_release.apk
   adb shell am start --user 10 -n com.crewguide.probe/.MainActivity
   ```
3. 처음 실행하면 알림 권한 안내와 처음 안내 3장이 나옵니다. [등록 없이 시작]을 누르거나 아이 별명을 등록합니다.

## 따라 해 보기 (Virtual Console)
1. Virtual Console에서 기어를 P로, 뒷좌석(왼쪽) 착석을 켭니다.
2. 앱 홈에서 [시작]을 누릅니다. 앉은 뒷자리가 자동으로 배정됩니다.
3. 에뮬레이터의 Virtual Console에는 뒷좌석 좌석별 벨트 조작 항목이 없어 뒷좌석 벨트 신호는 계속 '미체결'입니다. 안내가 세 번 나온 뒤(약 40초) 자리 칸에 [보호자 확인]이 뜨면 누릅니다. 아이를 등록하면서 "차량 벨트를 쓰지 않는 자리(카시트)"를 켜 두면 이 버튼이 바로 뜹니다.
4. 앞좌석 벨트는 Virtual Console의 Seat Belt 경고등으로 봅니다. 경고등이 켜져 있으면 "앞자리 벨트를 매 주세요"라고 안내하고 확인이 끝나지 않습니다.
5. 확인이 끝나면 음성 안내 뒤 내비 화면으로 넘어갑니다. 기어를 D로 바꾸면 "출발할게요."가 나옵니다. 주행 중에는 내비 화면이 보이고 앱은 음성으로만 안내합니다.
6. 다시 P로 바꾸면 앱이 앞으로 와서 "목적지에 도착했나요?"라고 묻습니다. [예, 도착했어요]를 누르거나 "네"라고 말하면 하차 안내와 스티커가 나옵니다.
7. Virtual Console에서 뒷문(왼쪽)을 열고 뒷좌석 착석을 끄면 "모두 내린 것 같아요 (문·착석 신호 기준)"로 마칩니다. 출발 전 확인 때 조수석에도 착석을 켜 두었다면 조수석 문을 열고 조수석 착석까지 꺼야 마칩니다(운전석은 제외).
8. 착석을 끄지 않고 두면 1분 뒤 "뒷자리에 앉은 신호가 아직 있어요" 안내가 나옵니다.
9. 내비 앱에서 목적지를 잡고 시작하면 출발 전 확인 음성에 "도착까지 약 N분 걸려요"가 붙습니다.

## 빌드
- 필요: JDK 17, Android SDK(compileSdk 35). Pleos SDK는 `settings.gradle.kts`의 Pleos 저장소에서 내려받습니다.
- 명령:
  ```
  ./gradlew assembleDebug assembleRelease
  ```
- release 빌드에는 개발용 모의 신호 도구(`app/src/debug`)가 들어가지 않습니다.
- debug 빌드에는 개발자 패널이 있어 뒷좌석 벨트·내비 신호를 모의로 넣을 수 있습니다. 화면에 "모의 신호"로 구분해 표시합니다.

## 구조 (`app/src/main/java/com/crewguide/probe/`)
| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | SDK 연결, 신호 구독·해제, 흐름 실행, 화면 전환 |
| `BoardingFlow.kt` | 탑승 준비 상태 기계, 유효 P + 플랫폼 허용일 때만 조작 |
| `SeatCheckRule.kt`, `ExitDoorRule.kt`, `TripAlerts.kt` | 출발 전 확인, 하차 판단, 두고 내림 안내 규칙 |
| `ArrivalAskRule.kt`, `ArrivalAnswerRule.kt` | 정차 질문과 음성 대답 판정 |
| `NaviTripEnd.kt`, `TripSession.kt`, `TripRestoreRule.kt` | 내비 이벤트, 이동 단위, 이동 상태 복원 |
| `TtsGuide.kt` | 음성 큐, 오디오 포커스 |
| `StageScreen.kt`, `SeatBoard.kt`, `HomeV6.kt`, `StickerBoard.kt` | 화면 |

## 원칙
- 차량을 제어하지 않습니다. 읽기만 합니다.
- 조작은 유효한 P와 플랫폼 허용이 함께 확인될 때만 엽니다. 주행 중에는 음성만 씁니다.
- 신호를 읽을 수 없으면 '확인 불가'로 따로 표시합니다.
- 신호 기준으로만 말합니다. 벨트를 바르게 맸는지나 안전한 출발을 보장하지 않으며, 차량의 경고와 보호자의 직접 확인을 대신하지 않습니다.
- 아이는 별명만 받습니다. 별명·스티커 기록·설정 등은 기기 안에만 저장합니다. 목적지 이름·주소·좌표는 읽지 않습니다.

## 라이선스·출처
- 글꼴 Jua: SIL Open Font License 1.1 (`THIRD_PARTY_LICENSES/`)
- 캐릭터 그림: 팀이 생성형 AI 도구로 직접 제작
- Pleos SDK: Pleos Playground 제공

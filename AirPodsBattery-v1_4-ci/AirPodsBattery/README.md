# AirPods Battery — 배터리 카드 UI

Kotlin + Jetpack Compose Android 앱입니다.

## 이번 변경

### 1.4 — 스캔 수신 진단 및 복구 수정

- Apple 제조사 ID 0x004C 필터를 적용했습니다.
- `neverForLocation`을 제거하고 정확한 위치/주변 기기 권한을 요청합니다.
  일부 BLE 비콘이 Android에서 걸러지는 문제를 확인하기 위한 변경입니다.
  위치 서비스를 켜고, 첫 검증은 앱 화면을 켠 상태에서 진행해주세요.
  이 버전으로 실제 기기의 백그라운드 수신이 보장되는 것은 아닙니다.
- '다시 검색'이 실제 stopScan/startScan을 수행합니다. 과도한 재등록을 피하려고
  재시작 간격을 최소 30초로 제한합니다.
- AD 레코드 앞의 짧은 Flags 항목 때문에 뒤쪽 제조사 데이터를 놓치는 오류를 수정했습니다.
- AirPodsPacket 태그에 scanStart, scanStop, scanFailed, connectionEvent,
  15초 간격 scanAlive appleResults 로그를 추가했습니다.
  scanAlive는 앱의 스캔 등록 상태를 뜻하며 실제 무선 수신 성공을 보장하지 않습니다.
- 배터리 메시지가 해석되지 않으면 자동 팝업은 표시하지 않습니다.
  '팝업 테스트'는 예시 값으로 화면 표시 권한을 확인하는 기능입니다.


- BLE 패킷이 해석되면 앱 안에서 흰색 하단 배터리 카드 표시.
- 포그라운드 서비스가 앱을 벗어난 뒤에도 BLE를 계속 감시하고, '다른 앱 위에 표시' 권한을
  허용하면 홈 화면/다른 앱 위에 배터리 오버레이를 표시.
- 연결된 A2DP/HFP 장치 이름(예: 사용자가 지정한 AirPods 이름)을 팝업 제목에 반영.
- 일시적인 BLE 스캔 실패나 블루투스 껐다 켜기 후에도 스캔을 자동 재시작.
- 첨부된 AirPods Pro 제품 예시 이미지, 모델명, 왼쪽/오른쪽/케이스 잔량 및 충전 상태.
- 오버레이 닫기 지원. 같은 수신 세션에서는 닫은 카드를 30초 동안 반복 표시하지 않음.
- 30초 동안 광고 수신이 없으면 이전 값과 카드를 지움. 이는 연결 해제를 의미하지 않음.
- 기기 없이 확인 가능한 '디자인 미리보기': 100/80/60%는 명시적으로 표시한 예시 값.
- 화면을 벗어나도 스캔을 유지. 블루투스 꺼짐/권한/스캔 실패 안내.
- 앱 아이콘, AndroidX 설정, Gradle 8.7 Wrapper 추가.
- 디버그 빌드 Logcat에 Apple 광고의 앞 16바이트와 길이 출력(태그 AirPodsPacket).
- 1.1: 임의의 바이트에서 0x07을 찾아 해석하던 동작을 제거했습니다. 표준 0x07 헤더와
  알려진 모델 ID가 맞는 광고만 배터리 값으로 사용하므로 다른 Apple BLE 광고가 배터리로
  표시되지 않습니다. 로그에는 진단용 BLE 주소와 RSSI도 함께 출력하고, 여러 기기가
  동시에 보이면 주소별 후보를 안정적으로 선택합니다.
- 1.2: 삼성 기기에서 `getManufacturerSpecificData()`가 전체 광고 레코드를 돌려주는 경우를
  보완했습니다. 전체 ScanRecord의 AD 구조에서 `FF 4C 00` 제조사 영역을 직접 추출하고,
  원시 레코드 길이와 추출 결과를 Logcat에 남깁니다.
- 1.3: Apple 제조사 영역에 여러 Continuity 메시지가 이어질 수 있는 형식을 처리합니다.
  예를 들어 `12 02 20 01 07 11 ...`은 `12` 메시지와 `07` 메시지로 길이 필드에 따라
  나눠 확인합니다. 내부의 완전한 `0x07` 프레임이라도 페어링 모드와 알려진 모델 ID가
  모두 맞을 때만 배터리로 사용하며, 로그의 `acm07=`에 추출된 길이/헤더/모델을 표시합니다.

## 구현 범위와 남은 검증

현재 '감지됨'은 블루투스 오디오 연결 이벤트가 아니라 AirPods BLE 광고 수신을 뜻합니다.
케이스를 열거나 이어버드를 꺼내 광고가 다시 전송될 때 팝업이 나타납니다. Android의 보안
정책상 앱 밖 팝업에는 시스템 설정에서 '다른 앱 위에 표시' 권한을 한 번 허용해야 합니다.
첨부 제품 이미지는 모델별 사진이 아니라 공통 예시 이미지입니다.

AirPodsPacket.kt는 표준 0x07 레이아웃(페어링 모드 2, 모델 3/4, 상태 5,
이어버드 배터리 6, 케이스/충전 7)을 기준으로 읽습니다. 케이스 배터리는 바이트 7의
하위 니블, 충전 플래그와 이어버드의 좌우 순서는 상위 니블과 상태 비트로 해석합니다.
0x004C 회사 ID가 포함된 입력과 알려진 짧은 구형 레이아웃도 호환합니다. 알 수 없는
모델이나 헤더가 맞지 않는 광고는 무시합니다. 사용자의 A2698(오른쪽)/A2699(왼쪽)/
A2700(케이스) AirPods Pro 2 Lightning은 BLE 모델 ID 0x1420으로 표시됩니다.

## Windows + 갤럭시 실행 순서

1. ZIP을 완전히 풀고 Android Studio에서 AirPodsBattery 폴더를 Open합니다.
2. Settings > Build, Execution, Deployment > Build Tools > Gradle에서 Gradle JDK를 17로 선택합니다.
3. SDK Manager에서 Android SDK Platform 34, Build-Tools 34.0.0, Platform-Tools를 설치합니다.
4. Gradle Sync를 실행합니다. 첫 실행 시 인터넷으로 Gradle 및 라이브러리를 받습니다.
5. 갤럭시 설정 > 휴대전화 정보 > 소프트웨어 정보 > 빌드번호를 7번 눌러 개발자 옵션을 켭니다.
6. 개발자 옵션에서 USB 디버깅을 켜고 데이터 전송 가능한 USB 케이블로 PC에 연결합니다.
7. 휴대전화의 USB 디버깅 허용 창을 승인합니다. Android Studio의 실행 기기 목록에서 갤럭시를 선택합니다.
8. app 실행 구성으로 Run을 누릅니다. 필요하면 삼성 USB 드라이버를 설치합니다.
9. 앱을 한 번 실행하고 주변 기기, 정확한 위치 권한(및 Android 13 이상 알림 권한)을 허용합니다.
10. 앱의 '팝업 권한 허용'을 눌러 AirPods Battery의 '다른 앱 위에 표시'를 켭니다.
11. '팝업 테스트'를 눌러 홈 화면 위 테스트 카드가 나타나는지 확인합니다.
12. 블루투스와 위치 서비스를 켜고, 양쪽 이어버드를 케이스에 넣은 채 뚜껑을 엽니다.
13. 삼성 배터리 설정에서 이 앱을 '제한 없음'으로 지정할 수 있습니다. 백그라운드 수신은 실기기에서 별도로 검증해야 합니다.
14. 실제 값 검증 시 Logcat 필터를 tag:AirPodsPacket 으로 설정하고 len=... data=... 및
    parsed modelId=... L=... R=... C=... 줄을 복사합니다.
    정확한 에어팟 모델명과 같은 시점의 아이폰 좌/우/케이스 배터리, 충전 여부를 함께 알려주세요.

명령줄 빌드: 프로젝트 루트에서 `.\gradlew.bat :app:assembleDebug`
APK 경로: `app/build/outputs/apk/debug/app-debug.apk`

## v1.4 설치 주의

이전 APK의 서명 키를 복구하지 못해 v1.4는 새 디버그 키로 서명했습니다.
기존 앱 위에 업데이트 설치할 수 없으므로 기존 AirPods Battery를 삭제한 뒤 설치해야 합니다.
삭제하면 앱 설정이 초기화되며 권한을 다시 허용해야 합니다.

## 검증 상태

- 코드 구조와 리소스 XML을 확인했습니다.
- v1.4 `:app:testDebugUnitTest :app:assembleDebug` 성공, 회귀 테스트 3개 통과, APK 서명(v2) 검증 완료.
- 이 작업 환경에는 실제 갤럭시와 AirPods가 없으므로 실기기 BLE 수신과 제조사별 광고 형식은
  아직 검증하지 않았습니다. 카드가 뜨지 않으면 Logcat의 `AirPodsPacket` 줄을 보내주세요.

## v1.4 로그 확인

Windows CMD에서 다음 명령을 실행한 뒤 앱 화면을 열고 '다시 검색'을 한 번 누르세요.

```bat
adb logcat -v time -s AirPodsPacket:D "*:S"
```

- `scanStart v=1.4 filter=004C`: 스캔 등록 시도.
- `scanAlive appleResults=0`: 등록 상태는 유지 중이나 Apple 필터 결과가 아직 없음.
- `scanFailed code=...`: Android가 스캔 실패를 보고함.
- `applePayload ... acm07=none`: Apple 데이터가 있지만 파서가 근접 메시지를 찾지 못함.
- `parsed ... L=... R=... C=...`: 배터리 파서가 승인한 값. 실제 값과 비교 필요.

'팝업 테스트'는 예시 데이터를 표시하므로 실제 잔량 검증에는 사용하지 마세요.
테스트 카드를 닫았다면 30초가 지난 후 실제 감지를 확인해주세요.

## 공식 문서

- Bottom sheet: https://developer.android.com/develop/ui/compose/components/bottom-sheets
- 빌드 버전: https://developer.android.com/build/releases/agp-8-5-0-release-notes
- 실기기 실행: https://developer.android.com/studio/run/device
- 오버레이 권한: https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)

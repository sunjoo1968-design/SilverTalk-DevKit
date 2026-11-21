# SilverTalk DevKit

제작자 SunjooAn · 갤럭시 앱 1.10 · ESP32 DevKit 펌웨어 1.8

SilverTalk는 ESP32-WROOM-32 박스의 물리 버튼으로 갤럭시에서 AI 음성 대화를 시작하고 마이크를 제어합니다. USB 또는 Wi-Fi로 박스를 연결하고, 보호자가 A·B 위치에 ChatGPT, Gemini Live, Sesame, Pi를 배정할 수 있습니다. 음성 입출력은 갤럭시의 마이크와 스피커를 사용합니다.

## 설치와 사용

[GitHub 배포 ZIP](https://github.com/sunjoo1968-design/SilverTalk-DevKit/releases/latest)에는 갤럭시 APK, 기존 박스 업데이트용 펌웨어, 새 박스 설치용 통합 펌웨어, 설치 안내와 배선도가 들어 있습니다.

- [설치와 보호자 설정](devkit-controller/docs/설치안내.md)
- [ESP32 펌웨어 플래싱 상세 안내](devkit-controller/docs/펌웨어-플래싱.md)
- [GPIO 배선도](devkit-controller/docs/배선도.svg)와 [부품표](devkit-controller/docs/부품표.md)

통화 중 대기 버튼을 3초 눌러 연속 대화를 켜고, 짧게 눌러 음소거 대기로 돌아옵니다. 선택 사양인 연속 대화 전용 버튼은 GPIO33, 전용 LED는 GPIO13에 연결합니다.

앱 1.10은 서비스 전환 중 선택이 다시 바뀌면 마지막 스위치 위치를 기준으로 통화를 유지합니다. 앱 1.9에서 수정한 짧은 Wi-Fi 지연 대응도 포함합니다. 다른 기종에서는 PD 충전과 USB 호스트 동시 동작 및 사용할 AI 앱의 마이크 제어를 설치할 때 확인하세요.

## 프로토타입 PCB

PCB r1.0은 30핀 ESP32-WROOM-32 DevKit을 두 줄의 암헤더에 끼우고, 스위치·LED·OLED를 JST XH로 연결하는 84 × 74 mm 캐리어입니다.

- [PCB 제작·조립 안내와 국내 부품 구매 링크](devkit-controller/hardware/carrier-r1.0/README.md)
- [PCB 주문용 거버 ZIP](https://github.com/sunjoo1968-design/SilverTalk-DevKit/releases/download/pcb-r1.0/SilverTalk-DevKit-Carrier-r1.0-JLCPCB-Gerber.zip)
- [KiCad 설계 전체 ZIP](https://github.com/sunjoo1968-design/SilverTalk-DevKit/releases/download/pcb-r1.0/SilverTalk-DevKit-Carrier-r1.0.zip)

## 프로토타입 케이스 r1.0

84 × 74 mm 캐리어용 본체, 교체형 전면판, 분리형 상판과 갤럭시 가로·세로 거치대를 포함합니다.

- [JLC3DP용 STL ZIP](https://github.com/sunjoo1968-design/SilverTalk-DevKit/releases/download/case-r1.0/SilverTalk-DevKit-Case-r1.0-JLC3DP.zip)
- [주문·조립 안내와 1:1 전면 도면](devkit-controller/hardware/enclosure-r1.0/README.md)

## 소스 빌드

### 갤럭시 앱

Windows PowerShell, JDK 17 이상, Android SDK Platform 36과 Build Tools 35.0.0을 사용합니다.

```powershell
.\devkit-controller\android\Build-Android.ps1 -AndroidSdk 'C:\Android\Sdk'
```

결과는 `devkit-controller/android/build/SilverTalk-DevKit-unsigned.apk`입니다. 새로 빌드한 APK는 본인 keystore로 서명할 수 있습니다. 배포 APK 위에 덮어 설치하려면 같은 서명이 필요하므로 일반 설치에는 GitHub Release의 서명된 APK를 사용하세요.

### ESP32 펌웨어

ESP-IDF 5.5.6 환경에서 실행합니다.

```powershell
Set-Location .\devkit-controller\firmware
idf.py set-target esp32
idf.py build
```

앱 영역 업데이트 파일은 `build/silvertalk_controller.bin`입니다. 초기 설치에는 `idf.py -p COM20 flash` 또는 배포 ZIP의 통합 파일을 사용합니다. COM 번호는 PC에서 확인한 값으로 바꿉니다.

## 제작 정보

음성 제어 흐름은 Sesami · ChatGPT Pro Micro V1 샘플을 참고했습니다. Android USB 시리얼 코드의 저작자와 라이선스는 [USB-SERIAL-LICENSE.txt](devkit-controller/android/USB-SERIAL-LICENSE.txt)에 있습니다.

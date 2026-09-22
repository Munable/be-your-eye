<div align="center">

# Be Your Eye

**남는 스마트폰을 시각 모니터로 바꿔 보세요.**

카메라를 맞추고 조건을 설정하세요. 조건이 충족되면 알려 드립니다.

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · **한국어** · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.md) [![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE) [![개발 미리보기](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**[데모 보기](#demo)** · **[소스에서 빌드](#get-started)** · [사용 안내](USER_MANUAL.md)

</div>

링크된 상세 안내 문서는 영어로 제공됩니다.

<a name="demo"></a>

## 표시창을 계속 확인하지 말고, 스마트폰에 맡기세요.

<table>
<tr>
<td width="45%" align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="280" src="docs/community/demos/numeric.gif" alt="전원 공급 장치의 숫자를 읽고 임계값 초과 이벤트를 기록하는 실제 앱 데모"></a></td>
<td width="55%">
<h3>수치가 8을 넘으면 스마트폰이 기록합니다.</h3>
<p>전원 공급 장치의 표시창, 카메라, 그리고 간단한 규칙 하나:</p>
<ol><li>표시창의 숫자를 읽습니다.</li><li>8을 넘는 상태가 1초간 유지되면 감지합니다.</li><li>읽은 값이 08.8일 때 이벤트를 기록하고 기기에 알림을 표시합니다.</li></ol>
<p><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4">23초 녹화 보기</a></p>
</td>
</tr>
</table>

Android 에뮬레이터에서 녹화한 앱 화면입니다. 재생 영상과 직접 그린 참조 이미지를 입력으로 사용했습니다. 사용 흐름을 보여 주며 실제 스마트폰의 정확도를 입증하지는 않습니다. [데모 출처와 결과](docs/community/demos/SOURCES.md).

- **카메라 화면은 내 기기에.** 인식은 스마트폰에서 실행되며 이미지도 기기에 보관됩니다.
- **계정, 구독, API 키 없이.** 모델을 다운로드하면 오프라인으로 모니터링할 수 있습니다.
- **일어난 일을 기록으로 확인하세요.** 기기에서 이력을 확인하고, 원한다면 다른 스마트폰으로 암호화된 문자 알림을 보낼 수 있습니다.

## 선택하고, 설정하고, 지켜보세요.

대상과 조건을 정하고 앱을 화면에 표시한 상태로 두세요. 필요할 때 이벤트 기록을 확인할 수 있습니다.

<table>
<tr><th>대상 선택</th><th>조건 설정</th><th>기록 확인</th></tr>
<tr><td align="center" width="33%"><img width="220" src="docs/community/images/home.png" alt="세 가지 모니터 생성 방법이 있는 홈 화면"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/condition.png" alt="숫자 임계값과 지속 시간 설정"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/history.png" alt="대상 데모에서 발생한 이벤트의 기기 내 기록"></td></tr>
</table>

## 숫자 외에도 지켜볼 수 있어요

<table>
<tr><th>사람이 나타나면 알아차리기 · 실험적 기능</th><th>사진으로 대상 지정하기 · 실험적 기능</th></tr>
<tr><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="240" src="docs/community/demos/person.gif" alt="재생 중인 거리 영상에서 사람을 감지하는 앱"></a></td><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="240" src="docs/community/demos/reference.gif" alt="그림으로 된 대상을 참조 이미지와 비교하는 앱"></a></td></tr>
<tr><td>카탈로그에서 지원하는 대상을 선택합니다. 데모는 사람이라는 범주를 감지하며 신원을 식별하지 않습니다.</td><td>참조 사진 3~20장을 제공합니다. 데모는 직접 그린 테스트 이미지를 비교합니다.</td></tr>
</table>

## 한 대는 지켜보고, 다른 한 대는 알려 줍니다.

QR 코드로 스마트폰을 페어링하세요. 선택한 중계 서비스를 통해 암호화된 문자를 보내며, 이미지는 모니터링 스마트폰에 남습니다. [페어링 안내](docs/community/PAIRING.md).

<table>
<tr><th>알림 보내기</th><th>다른 스마트폰에서 받기</th></tr>
<tr><td align="center" width="50%"><img width="240" src="docs/community/images/paired-send.png" alt="암호화된 테스트 알림을 보낸 기기"></td><td align="center" width="50%"><img width="240" src="docs/community/images/paired-receive.png" alt="동일한 테스트 알림을 표시하는 별도의 수신 기기"></td></tr>
</table>

이미지는 공개 중계 서비스를 통해 두 에뮬레이터가 테스트 알림을 주고받는 모습입니다. 전달 여부는 네트워크, 중계 서비스 상태, Android 배터리 설정에 따라 달라집니다.

<a name="get-started"></a>

## Be Your Eye 사용해 보기

**개발 미리보기 — 공개 APK는 아직 없습니다.** Community 앱을 빌드한 뒤 사용 안내를 따라 주세요. Releases에는 현재 모델 파일만 있습니다.

**[소스에서 빌드](docs/community/BUILD.md)** · [사용 안내](USER_MANUAL.md)

**모니터링 스마트폰:** Android 8 이상, arm64, RAM 8 GB. 스마트폰을 고정하고 전원을 연결한 뒤 앱이 보이도록 유지하세요. 앱을 전환하거나 화면을 잠그면 모니터링이 중지됩니다.

현재 주력 기능은 숫자 읽기이며, 나머지 두 방식은 실험적입니다. 지원 대상은 카탈로그에 등록된 항목으로 제한됩니다. 실제 스마트폰에서의 정확도와 장시간 작동 신뢰성은 아직 검증되지 않았습니다. 안전 경보 장치가 아닙니다. [기기 요구 사항과 제한](docs/community/DEVICE_SUPPORT.md).

## 함께 만들어 가요

사용 상황을 시험하거나, 버그를 보고하거나, 번역을 개선해 주세요. [기여 안내](CONTRIBUTING.md) · [아키텍처](docs/ARCHITECTURE.md) · [보안](SECURITY.md).

## 라이선스

**상업적 용도를 포함하여 무료로 사용·수정·재배포할 수 있습니다.** 프로젝트 자체 코드는 [Apache-2.0](LICENSE)으로 제공되며 별도 허가는 필요하지 않습니다. 라이선스에서 요구하는 라이선스 문서, 출처 표시 및 변경 고지를 유지해 주세요. [모델](docs/community/MODELS.md), [데모 영상](docs/community/demos/SOURCES.md), [의존성](THIRD_PARTY_NOTICES.md)에는 각각의 라이선스가 적용됩니다.

<div align="center">

# 幫你盯 · Be Your Eye

**讓閒置手機，替你盯著。**

對準相機，設好條件，發生時提醒你。

[English](README.md) · [简体中文](README.zh-CN.md) · **繁體中文** · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.md) [![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE) [![開發預覽版](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**[觀看示範](#demo)** · **[從原始碼建置](#get-started)** · [使用指南](USER_MANUAL.md)

</div>

## 攝影機就是連結

讓原本只會顯示的裝置，也能提醒你。把一台相容的閒置手機對準顯示幕，設好讀數條件，就能把看得見的變化變成事件，無須改裝被觀察的裝置。辨識與紀錄都留在手機上。

[產品的出發點與取捨（英文／簡體中文）](PRODUCT_POSITIONING.md)。

以下連結的詳細指南以英文提供。

<a name="demo"></a>

## 不用反覆查看儀表，讓手機幫你盯著。

<table>
<tr>
<td width="45%" align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="280" src="docs/community/demos/numeric.gif" alt="應用程式實錄：讀取電源儀表並觸發超限事件"></a></td>
<td width="55%">
<h3>讀數超過 8，手機就留下紀錄。</h3>
<p>一個電源儀表、一個相機，再加一條簡單規則：</p>
<ol><li>盯住螢幕上的數字。</li><li>超過 8 並持續 1 秒時觸發。</li><li>在讀數 08.8 時記錄事件，並顯示本機通知。</li></ol>
<p><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4">觀看 23 秒實錄</a></p>
</td>
</tr>
</table>

示範是在 Android 模擬器中錄製的應用程式操作，輸入為重播影片與繪製的參考圖案；展示操作流程，不代表實機準確率。 [示範來源與結果](docs/community/demos/SOURCES.md).

- **畫面留在你手上。** 辨識在手機上執行，圖片保留在本機。
- **無須帳號、訂閱或 API 金鑰。** 下載模型後即可離線監看。
- **發生過什麼，隨時查看。** 在本機查看紀錄，也可向另一支手機傳送加密文字提醒。

## 選好目標，設好條件，開始監看

選擇要監看的目標、設定條件，並保持應用程式可見。需要時查看事件紀錄。

<table>
<tr><th>選擇目標</th><th>設定條件</th><th>查看紀錄</th></tr>
<tr><td align="center" width="33%"><img width="220" src="docs/community/images/home.png" alt="首頁的三種監看建立方式"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/condition.png" alt="設定數字門檻與持續時間"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/history.png" alt="本機歷史中的目標示範事件"></td></tr>
</table>

## 也可以監看這些

<table>
<tr><th>有人出現時提醒你 · 實驗功能</th><th>用圖片指定要找的目標 · 實驗功能</th></tr>
<tr><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="240" src="docs/community/demos/person.gif" alt="應用程式在重播的街景影片中偵測行人"></a></td><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="240" src="docs/community/demos/reference.gif" alt="應用程式將繪製的目標與參考圖片比對"></a></td></tr>
<tr><td>從目錄中選擇支援的目標。示範偵測的是行人類別，不辨識身分。</td><td>提供 3–20 張參考圖片。示範比對的是繪製的測試圖案。</td></tr>
</table>

## 一支手機監看，另一支手機提醒你

掃描 QR 碼配對手機，加密文字透過你選擇的中繼服務傳送，圖片留在監看端。 [配對指南](docs/community/PAIRING.md).

<table>
<tr><th>傳送提醒</th><th>另一支手機收到提醒</th></tr>
<tr><td align="center" width="50%"><img width="240" src="docs/community/images/paired-send.png" alt="傳送端已送出加密測試提醒"></td><td align="center" width="50%"><img width="240" src="docs/community/images/paired-receive.png" alt="另一接收端顯示相同的測試提醒"></td></tr>
</table>

圖中是兩個模擬器透過公共中繼服務收發測試提醒。送達受網路、中繼服務可用性及 Android 電池設定影響。

<a name="get-started"></a>

## 開始使用幫你盯

**開發預覽版，尚無公開 APK。** 從原始碼建置 Community 應用程式，再依使用指南操作。Releases 目前只有模型檔案。

**[從原始碼建置](docs/community/BUILD.md)** · [使用指南](USER_MANUAL.md)

**監看手機需求：** Android 8+、arm64、8 GB 記憶體。固定手機、持續供電並保持應用程式可見；切換應用程式或鎖定螢幕會停止監看。

數字讀取是目前的主要路線，另外兩種仍屬實驗功能。支援目標來自有限目錄。實機準確率與持續運作可靠性尚未驗證，不適合作為安全警報器。 [裝置需求與限制](docs/community/DEVICE_SUPPORT.md).

## 一起把它做好

試一個場景、回報問題，或改善翻譯。 [參與貢獻](CONTRIBUTING.md) · [架構](docs/ARCHITECTURE.md) · [安全政策](SECURITY.md).

## 授權

**可免費使用、修改及散布，包括商業用途。** 第一方程式碼採用 [Apache-2.0](LICENSE)，無須另外申請許可；請保留授權要求的授權條款、署名及修改聲明。[模型](docs/community/MODELS.md)、[示範素材](docs/community/demos/SOURCES.md)及[相依套件](THIRD_PARTY_NOTICES.md)仍遵循各自的授權。

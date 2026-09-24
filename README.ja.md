<div align="center">

# Be Your Eye

**使っていないスマホを、見守るカメラに。**

カメラを向けて、条件を設定。条件を満たしたら通知します。

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · **日本語** · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.md) [![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE) [![開発プレビュー](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**[デモを見る](#demo)** · **[ソースからビルド](#get-started)** · [使い方](USER_MANUAL.md)

</div>

## カメラが、つなぎ役になる

表示するだけだった機器から、通知を受け取れるように。対応する使っていないスマホを表示に向け、数値の条件を設定すると、目に見える変化をイベントにできます。監視する機器の改造は不要です。認識も履歴の保存もスマホ内で行います。

[製品の考え方と設計上の選択（英語・簡体字中国語）](docs/PRODUCT_POSITIONING.md)。

リンク先の詳細ガイドは英語です。

<a name="demo"></a>

## 表示を何度も確認せず、スマホに見てもらう。

<table>
<tr>
<td width="45%" align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="280" src="docs/community/demos/numeric.gif" alt="電源装置の表示を読み取り、しきい値の超過を検知するアプリのデモ"></a></td>
<td width="55%">
<h3>数値が 8 を超えたら、スマホが記録。</h3>
<p>電源装置の表示、カメラ、そしてシンプルなルール：</p>
<ol><li>表示されている数値を読み取る。</li><li>8 を超えた状態が 1 秒続いたら検知する。</li><li>読取値 08.8 でイベントを記録し、端末に通知を表示する。</li></ol>
<p><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4">23 秒の録画を見る</a></p>
</td>
</tr>
</table>

Android エミュレーターで録画したアプリの操作です。入力には再生映像と図形の参照画像を使用しています。操作の流れを示すもので、実機での精度を示すものではありません。 [デモの出典と結果](docs/community/demos/SOURCES.md).

- **映像は手元に。** 認識はスマホ内で動作し、画像も端末に保存します。
- **アカウント、サブスクリプション、API キーは不要。** モデルをダウンロードすれば、オフラインで監視できます。
- **起きたことを、あとから確認。** 履歴を端末で確認でき、別のスマホに暗号化されたテキスト通知を送ることもできます。

## 選ぶ。設定する。見守る。

対象と条件を選び、アプリを画面に表示したままにします。必要なときにイベント履歴を確認できます。

<table>
<tr><th>対象を選ぶ</th><th>条件を設定</th><th>履歴を確認</th></tr>
<tr><td align="center" width="33%"><img width="220" src="docs/community/images/home.png" alt="監視の作成方法を三つから選べるホーム画面"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/condition.png" alt="数値のしきい値と継続時間の設定"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/history.png" alt="対象検出デモのイベントを表示した端末内の履歴"></td></tr>
</table>

## 数値以外も対象に

<table>
<tr><th>人が現れたら気づく · 実験的機能</th><th>写真から対象を指定する · 実験的機能</th></tr>
<tr><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="240" src="docs/community/demos/person.gif" alt="再生した街頭映像から人を検出するアプリ"></a></td><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="240" src="docs/community/demos/reference.gif" alt="図形の対象を参照画像と照合するアプリ"></a></td></tr>
<tr><td>カタログから対応する対象を選択します。デモは人というカテゴリを検出し、個人を識別するものではありません。</td><td>参照画像を 3〜20 枚用意します。デモでは図形のテスト画像を照合しています。</td></tr>
</table>

## 1 台が見守り、もう 1 台がお知らせ。

QR コードでスマホをペアリング。選択したリレー経由で暗号化テキストを送り、画像は監視側のスマホに残します。 [ペアリングガイド](docs/community/PAIRING.md).

<table>
<tr><th>通知を送信</th><th>別のスマホで受信</th></tr>
<tr><td align="center" width="50%"><img width="240" src="docs/community/images/paired-send.png" alt="暗号化したテスト通知を送信した端末"></td><td align="center" width="50%"><img width="240" src="docs/community/images/paired-receive.png" alt="同じテスト通知を表示する別の受信端末"></td></tr>
</table>

画像は、公開リレーを介して二つのエミュレーター間でテスト通知を送受信したものです。配信はネットワーク、リレーの稼働状況、Android のバッテリー設定に左右されます。

<a name="get-started"></a>

## Be Your Eye を試す

**開発プレビュー：公開 APK はまだありません。** Community アプリをビルドし、使い方に沿って設定してください。Releases には現在、モデルファイルのみを掲載しています。

**[ソースからビルド](docs/community/BUILD.md)** · [使い方](USER_MANUAL.md)

**監視側のスマホ：** Android 8 以降、arm64、RAM 8 GB。端末を固定して給電し、アプリを表示したままにしてください。アプリの切り替えや画面ロックで監視は停止します。

現在は数値の読み取りが中心で、ほかの二つは実験的機能です。対象はカタログに登録されたものに限られます。実機での精度と長時間動作の信頼性は未検証です。安全を保証する警報装置ではありません。 [端末要件と制限](docs/community/DEVICE_SUPPORT.md).

## 一緒に育てる

場面を試す、不具合を報告する、翻訳を改善するなどの協力を歓迎します。 [貢献ガイド](CONTRIBUTING.md) · [アーキテクチャ](docs/ARCHITECTURE.md) · [セキュリティ](SECURITY.md).

## ライセンス

**商用を含め、無償で使用・改変・再配布できます。** 本プロジェクトのコードは [Apache-2.0](LICENSE) で提供され、個別の許可は不要です。ライセンスで求められるライセンス文書、帰属表示、変更の明示を維持してください。[モデル](docs/community/MODELS.md)、[デモ映像](docs/community/demos/SOURCES.md)、[依存ライブラリ](THIRD_PARTY_NOTICES.md)にはそれぞれのライセンスが適用されます。

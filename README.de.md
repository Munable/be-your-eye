<div align="center">

# Be Your Eye

**Mach aus einem ungenutzten Smartphone einen visuellen Monitor.**

Kamera ausrichten. Bedingung festlegen. Bei Erfüllung eine Meldung erhalten.

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · **Deutsch** · [Português (Brasil)](README.pt-BR.md)

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.md) [![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE) [![Entwicklungsvorschau](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**[Demo ansehen](#demo)** · **[Aus Quellcode bauen](#get-started)** · [Anleitung](USER_MANUAL.md)

</div>

Die verlinkten ausführlichen Anleitungen sind auf Englisch.

<a name="demo"></a>

## Schau nicht ständig auf die Anzeige. Lass dein Smartphone aufpassen.

<table>
<tr>
<td width="45%" align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="280" src="docs/community/demos/numeric.gif" alt="Echte App-Demo: Ablesen einer Netzteilanzeige und Erkennen einer Grenzwertüberschreitung"></a></td>
<td width="55%">
<h3>Der Messwert steigt über 8. Dein Smartphone erkennt es.</h3>
<p>Die Anzeige eines Netzteils, eine Kamera und eine einfache Regel:</p>
<ol><li>Die Zahl auf der Anzeige ablesen.</li><li>Auslösen, wenn der Wert eine Sekunde lang über 8 liegt.</li><li>Bei 08.8 ein Ereignis speichern und eine lokale Benachrichtigung anzeigen.</li></ol>
<p><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4">Die 23-sekündige Aufnahme ansehen</a></p>
</td>
</tr>
</table>

App-Aufnahmen aus einem Android-Emulator mit abgespieltem Videomaterial und einer gezeichneten Referenzvorlage. Sie zeigen den Ablauf, nicht die Genauigkeit auf echten Smartphones. [Quellen und Ergebnisse der Demos](docs/community/demos/SOURCES.md).

- **Deine Bilder bleiben bei dir.** Die Erkennung läuft auf dem Smartphone; die Bilder bleiben dort.
- **Kein Konto. Kein Abo. Kein API-Schlüssel.** Modelle einmal herunterladen, danach offline überwachen.
- **Ein Verlauf der Ereignisse.** Verlauf lokal ansehen und auf Wunsch eine verschlüsselte Textmeldung an ein anderes Smartphone senden.

## Auswählen. Einstellen. Beobachten.

Wähle ein Ziel, lege die Bedingung fest und halte die App sichtbar. Sieh dir bei Bedarf die gespeicherten Ereignisse an.

<table>
<tr><th>Ziel auswählen</th><th>Bedingung festlegen</th><th>Ereignisse ansehen</th></tr>
<tr><td align="center" width="33%"><img width="220" src="docs/community/images/home.png" alt="Startbildschirm mit drei Möglichkeiten, einen Monitor anzulegen"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/condition.png" alt="Einstellung eines numerischen Grenzwerts und einer Dauer"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/history.png" alt="Lokaler Verlauf mit Ereignissen aus den Zielerkennungs-Demos"></td></tr>
</table>

## Mehr als nur Zahlen

<table>
<tr><th>Bemerken, wenn eine Person erscheint · Experimentell</th><th>Ein Ziel anhand deiner Bilder erkennen · Experimentell</th></tr>
<tr><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="240" src="docs/community/demos/person.gif" alt="App erkennt Personen in abgespieltem Straßenmaterial"></a></td><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="240" src="docs/community/demos/reference.gif" alt="App gleicht ein gezeichnetes Ziel mit Referenzbildern ab"></a></td></tr>
<tr><td>Wähle ein unterstütztes Ziel aus dem Katalog. Die Demo erkennt die Kategorie Person, keine Identitäten.</td><td>Füge 3–20 Referenzbilder hinzu. Die Demo gleicht ein gezeichnetes Testmotiv ab.</td></tr>
</table>

## Ein Smartphone beobachtet. Ein anderes informiert dich.

Kopple die Smartphones per QR-Code. Verschlüsselter Text wird über einen Relay-Dienst deiner Wahl übertragen; Bilder bleiben auf dem überwachenden Smartphone. [Kopplungsanleitung](docs/community/PAIRING.md).

<table>
<tr><th>Meldung senden</th><th>Auf einem anderen Smartphone empfangen</th></tr>
<tr><td align="center" width="50%"><img width="240" src="docs/community/images/paired-send.png" alt="Sender nach dem Versand einer verschlüsselten Testmeldung"></td><td align="center" width="50%"><img width="240" src="docs/community/images/paired-receive.png" alt="Separates Empfangsgerät mit derselben Testmeldung"></td></tr>
</table>

Gezeigt wird eine Testmeldung zwischen zwei Emulatoren über einen öffentlichen Relay-Dienst. Die Zustellung hängt vom Netzwerk, dem Relay-Dienst und den Android-Akkueinstellungen ab.

<a name="get-started"></a>

## Be Your Eye ausprobieren

**Entwicklungsvorschau — noch keine öffentliche APK.** Baue die Community-App und folge der Anleitung. Releases enthält derzeit nur Modelldateien.

**[Aus Quellcode bauen](docs/community/BUILD.md)** · [Anleitung](USER_MANUAL.md)

**Überwachendes Smartphone:** Android 8 oder neuer, arm64, 8 GB RAM. Fest aufstellen, mit Strom versorgen und die App sichtbar lassen. Ein App-Wechsel oder Sperren des Bildschirms beendet die Überwachung.

Das Ablesen von Zahlen ist die Hauptfunktion; die beiden anderen Wege sind experimentell. Unterstützte Ziele stammen aus einem begrenzten Katalog. Genauigkeit auf echten Smartphones und Zuverlässigkeit im Dauerbetrieb sind noch nicht verifiziert. Kein Sicherheitsalarmsystem. [Geräteanforderungen und Grenzen](docs/community/DEVICE_SUPPORT.md).

## Gestalte mit

Probiere einen Anwendungsfall aus, melde einen Fehler oder verbessere eine Übersetzung. [Mitwirken](CONTRIBUTING.md) · [Architektur](docs/ARCHITECTURE.md) · [Sicherheit](SECURITY.md).

## Lizenz

**Kostenlos nutzen, verändern und weitergeben — auch kommerziell.** Der eigene Projektcode steht unter [Apache-2.0](LICENSE); eine gesonderte Erlaubnis ist nicht nötig. Behalte die vorgeschriebenen Lizenz-, Urheber- und Änderungshinweise bei. [Modelle](docs/community/MODELS.md), [Demomaterial](docs/community/demos/SOURCES.md) und [Abhängigkeiten](THIRD_PARTY_NOTICES.md) behalten ihre jeweiligen Lizenzen.

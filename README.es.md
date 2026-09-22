<div align="center">

# Be Your Eye

**Convierte un móvil que ya no usas en un monitor visual.**

Apunta la cámara. Define una condición. Recibe un aviso cuando se cumpla.

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · **Español** · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.md) [![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE) [![Versión preliminar](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**[Ver la demo](#demo)** · **[Compilar desde el código](#get-started)** · [Guía de uso](USER_MANUAL.md)

</div>

Las guías detalladas enlazadas están en inglés.

<a name="demo"></a>

## Deja de comprobar la pantalla. Que tu móvil la vigile.

<table>
<tr>
<td width="45%" align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="280" src="docs/community/demos/numeric.gif" alt="Demo real de la app: lectura de una fuente de alimentación y detección de un umbral superado"></a></td>
<td width="55%">
<h3>La lectura supera 8. Tu móvil lo detecta.</h3>
<p>La pantalla de una fuente de alimentación, una cámara y una regla sencilla:</p>
<ol><li>Lee el número de la pantalla.</li><li>Activa el aviso si supera 8 durante un segundo.</li><li>Registra el evento con la lectura 08.8 y muestra una notificación local.</li></ol>
<p><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4">Ver la grabación de 23 segundos</a></p>
</td>
</tr>
</table>

Grabaciones de la app en un emulador Android, con vídeos reproducidos y un dibujo como referencia. Muestran el flujo de uso, no la precisión en un móvil real. [Fuentes y resultados de las demos](docs/community/demos/SOURCES.md).

- **Tu cámara sigue siendo tuya.** El reconocimiento se ejecuta en el móvil y las imágenes se quedan allí.
- **Sin cuenta, suscripción ni clave de API.** Descarga los modelos una vez y después vigila sin conexión.
- **Un historial de lo que ha pasado.** Consulta el historial local y, si quieres, envía un aviso de texto cifrado a otro móvil.

## Elige. Configura. Vigila.

Elige qué observar, define la condición y mantén la app visible. Consulta los eventos cuando los necesites.

<table>
<tr><th>Elige un objetivo</th><th>Define una condición</th><th>Consulta los eventos</th></tr>
<tr><td align="center" width="33%"><img width="220" src="docs/community/images/home.png" alt="Pantalla inicial con tres formas de crear un monitor"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/condition.png" alt="Configuración de un umbral numérico y su duración"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/history.png" alt="Historial local con eventos de las demos de detección"></td></tr>
</table>

## Más formas de observar

<table>
<tr><th>Detecta cuándo aparece una persona · Experimental</th><th>Busca un objetivo a partir de tus fotos · Experimental</th></tr>
<tr><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="240" src="docs/community/demos/person.gif" alt="App detectando personas en un vídeo de una calle"></a></td><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="240" src="docs/community/demos/reference.gif" alt="App comparando un dibujo con imágenes de referencia"></a></td></tr>
<tr><td>Selecciona un objetivo compatible del catálogo. La demo detecta personas, no identidades.</td><td>Añade entre 3 y 20 imágenes de referencia. La demo compara un dibujo de prueba.</td></tr>
</table>

## Un móvil vigila. Otro te avisa.

Vincula los móviles con un código QR. El texto cifrado pasa por el servicio de retransmisión que elijas; las imágenes se quedan en el móvil que vigila. [Guía de vinculación](docs/community/PAIRING.md).

<table>
<tr><th>Envía un aviso</th><th>Recíbelo en otro móvil</th></tr>
<tr><td align="center" width="50%"><img width="240" src="docs/community/images/paired-send.png" alt="Emisor tras enviar un aviso de prueba cifrado"></td><td align="center" width="50%"><img width="240" src="docs/community/images/paired-receive.png" alt="Otro dispositivo receptor mostrando el mismo aviso de prueba"></td></tr>
</table>

Las imágenes muestran un aviso de prueba entre dos emuladores a través de un servicio público de retransmisión. La entrega depende de la red, del servicio y de los ajustes de batería de Android.

<a name="get-started"></a>

## Prueba Be Your Eye

**Versión preliminar de desarrollo: todavía no hay un APK público.** Compila la app Community y sigue la guía de uso. Releases solo contiene archivos de modelos por ahora.

**[Compilar desde el código](docs/community/BUILD.md)** · [Guía de uso](USER_MANUAL.md)

**Móvil de monitorización:** Android 8 o posterior, arm64 y 8 GB de RAM. Déjalo fijo, conectado a la corriente y con la app visible. Cambiar de app o bloquear la pantalla detiene la monitorización.

La lectura numérica es la función principal; las otras dos vías son experimentales. Los objetivos compatibles se limitan a un catálogo. La precisión en móviles reales y la fiabilidad continuada aún no se han verificado. No es una alarma de seguridad. [Requisitos y limitaciones](docs/community/DEVICE_SUPPORT.md).

## Ayuda a mejorarlo

Prueba un caso de uso, informa de un error o mejora una traducción. [Contribuir](CONTRIBUTING.md) · [Arquitectura](docs/ARCHITECTURE.md) · [Seguridad](SECURITY.md).

## Licencia

El código propio se publica bajo [Apache-2.0](LICENSE). Los [modelos](docs/community/MODELS.md), los [vídeos de las demos](docs/community/demos/SOURCES.md) y las [dependencias](THIRD_PARTY_NOTICES.md) conservan sus propias licencias.

<div align="center">

# Be Your Eye

**Transforme um celular que você não usa em um monitor visual.**

Aponte a câmera. Defina uma condição. Receba um alerta quando ela acontecer.

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · **Português (Brasil)**

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.md) [![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE) [![Prévia de desenvolvimento](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**[Ver a demonstração](#demo)** · **[Compilar o código](#get-started)** · [Guia de uso](USER_MANUAL.md)

</div>

Os guias detalhados nos links estão em inglês.

<a name="demo"></a>

## Pare de conferir o visor. Deixe o celular observar.

<table>
<tr>
<td width="45%" align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="280" src="docs/community/demos/numeric.gif" alt="Demonstração real do app lendo o visor de uma fonte e detectando um limite ultrapassado"></a></td>
<td width="55%">
<h3>A leitura passa de 8. Seu celular percebe.</h3>
<p>O visor de uma fonte de alimentação, uma câmera e uma regra simples:</p>
<ol><li>Ler o número no visor.</li><li>Acionar quando ficar acima de 8 por um segundo.</li><li>Registrar o evento na leitura 08.8 e mostrar uma notificação local.</li></ol>
<p><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4">Ver a gravação de 23 segundos</a></p>
</td>
</tr>
</table>

Gravações do app em um emulador Android, usando vídeos reproduzidos e uma referência desenhada. Elas mostram o fluxo de uso, não a precisão em um celular real. [Fontes e resultados das demonstrações](docs/community/demos/SOURCES.md).

- **Suas imagens ficam com você.** O reconhecimento roda no celular, e as imagens ficam nele.
- **Sem conta, assinatura ou chave de API.** Baixe os modelos uma vez e depois monitore sem conexão.
- **Um histórico do que aconteceu.** Consulte o histórico local e, se quiser, envie um alerta de texto criptografado para outro celular.

## Escolha. Configure. Monitore.

Escolha o que observar, defina a condição e mantenha o app visível. Consulte os eventos quando precisar.

<table>
<tr><th>Escolher um alvo</th><th>Definir uma condição</th><th>Consultar eventos</th></tr>
<tr><td align="center" width="33%"><img width="220" src="docs/community/images/home.png" alt="Tela inicial com três formas de criar um monitor"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/condition.png" alt="Configuração de um limite numérico e sua duração"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/history.png" alt="Histórico local com eventos das demonstrações de detecção"></td></tr>
</table>

## Outras formas de observar

<table>
<tr><th>Perceber quando uma pessoa aparece · Experimental</th><th>Encontrar um alvo a partir das suas fotos · Experimental</th></tr>
<tr><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="240" src="docs/community/demos/person.gif" alt="App detectando pessoas em um vídeo de rua reproduzido"></a></td><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="240" src="docs/community/demos/reference.gif" alt="App comparando um alvo desenhado com imagens de referência"></a></td></tr>
<tr><td>Escolha um alvo compatível no catálogo. A demonstração detecta pessoas, não identidades.</td><td>Forneça de 3 a 20 imagens de referência. A demonstração compara um desenho de teste.</td></tr>
</table>

## Um celular observa. Outro avisa você.

Pareie os celulares por código QR. O texto criptografado passa pelo serviço de retransmissão que você escolher; as imagens ficam no celular que monitora. [Guia de pareamento](docs/community/PAIRING.md).

<table>
<tr><th>Enviar um alerta</th><th>Receber em outro celular</th></tr>
<tr><td align="center" width="50%"><img width="240" src="docs/community/images/paired-send.png" alt="Remetente após enviar um alerta de teste criptografado"></td><td align="center" width="50%"><img width="240" src="docs/community/images/paired-receive.png" alt="Outro dispositivo receptor exibindo o mesmo alerta de teste"></td></tr>
</table>

As imagens mostram um alerta de teste entre dois emuladores por um serviço público de retransmissão. A entrega depende da rede, da disponibilidade do serviço e das configurações de bateria do Android.

<a name="get-started"></a>

## Experimente o Be Your Eye

**Prévia de desenvolvimento — ainda não há um APK público.** Compile o app Community e siga o guia de uso. Releases contém apenas arquivos de modelos no momento.

**[Compilar o código](docs/community/BUILD.md)** · [Guia de uso](USER_MANUAL.md)

**Celular de monitoramento:** Android 8 ou superior, arm64 e 8 GB de RAM. Mantenha-o fixo, ligado à energia e com o app visível. Trocar de app ou bloquear a tela interrompe o monitoramento.

A leitura numérica é a função principal; as outras duas opções são experimentais. Os alvos compatíveis se limitam a um catálogo. A precisão em celulares reais e a confiabilidade em uso prolongado ainda não foram verificadas. Não é um alarme de segurança. [Requisitos e limitações](docs/community/DEVICE_SUPPORT.md).

## Ajude a melhorar

Teste um cenário, relate um problema ou melhore uma tradução. [Como contribuir](CONTRIBUTING.md) · [Arquitetura](docs/ARCHITECTURE.md) · [Segurança](SECURITY.md).

## Licença

**Uso, modificação e redistribuição gratuitos, inclusive para fins comerciais.** O código próprio do projeto usa [Apache-2.0](LICENSE), sem necessidade de autorização adicional. Preserve as licenças, atribuições e avisos de alterações exigidos. Os [modelos](docs/community/MODELS.md), os [vídeos das demonstrações](docs/community/demos/SOURCES.md) e as [dependências](THIRD_PARTY_NOTICES.md) mantêm suas próprias licenças.

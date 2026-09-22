<div align="center">

# Be Your Eye

**Transformez un téléphone inutilisé en moniteur visuel.**

Orientez la caméra. Définissez une condition. Recevez une alerte quand elle est remplie.

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · **Français** · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.md) [![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE) [![Version de développement](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**[Voir la démo](#demo)** · **[Compiler les sources](#get-started)** · [Guide d’utilisation](USER_MANUAL.md)

</div>

Les guides détaillés accessibles par les liens sont en anglais.

<a name="demo"></a>

## Ne vérifiez plus sans cesse l’afficheur. Confiez-le à votre téléphone.

<table>
<tr>
<td width="45%" align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="280" src="docs/community/demos/numeric.gif" alt="Démonstration réelle de l’application lisant un afficheur et détectant un dépassement de seuil"></a></td>
<td width="55%">
<h3>La valeur dépasse 8. Votre téléphone le détecte.</h3>
<p>L’afficheur d’une alimentation, une caméra et une règle simple :</p>
<ol><li>Lire le nombre sur l’afficheur.</li><li>Déclencher si la valeur dépasse 8 pendant une seconde.</li><li>Enregistrer un événement à la valeur 08.8 et afficher une notification locale.</li></ol>
<p><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4">Voir les 23 secondes de démonstration</a></p>
</td>
</tr>
</table>

Captures de l’application dans un émulateur Android, avec des vidéos rejouées et un dessin de référence. Elles montrent le fonctionnement, pas la précision sur un vrai téléphone. [Sources et résultats des démos](docs/community/demos/SOURCES.md).

- **Vos images restent chez vous.** La reconnaissance fonctionne sur le téléphone et les images y restent.
- **Sans compte, abonnement ni clé API.** Téléchargez les modèles une fois, puis surveillez hors connexion.
- **Un historique de ce qui se passe.** Consultez votre historique local et envoyez, si vous le souhaitez, une alerte texte chiffrée à un autre téléphone.

## Choisir. Configurer. Surveiller.

Choisissez la cible, définissez la condition et gardez l’application visible. Consultez les événements quand vous en avez besoin.

<table>
<tr><th>Choisir une cible</th><th>Définir une condition</th><th>Consulter les événements</th></tr>
<tr><td align="center" width="33%"><img width="220" src="docs/community/images/home.png" alt="Écran d’accueil avec trois façons de créer un moniteur"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/condition.png" alt="Réglage d’un seuil numérique et de sa durée"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/history.png" alt="Historique local des événements des démos de détection"></td></tr>
</table>

## Au-delà des nombres

<table>
<tr><th>Détecter l’apparition d’une personne · Expérimental</th><th>Repérer une cible à partir de vos images · Expérimental</th></tr>
<tr><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="240" src="docs/community/demos/person.gif" alt="Application détectant des personnes dans une vidéo de rue rejouée"></a></td><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="240" src="docs/community/demos/reference.gif" alt="Application comparant un dessin aux images de référence"></a></td></tr>
<tr><td>Choisissez une cible prise en charge dans le catalogue. La démo détecte la catégorie « personne », pas l’identité.</td><td>Fournissez 3 à 20 images de référence. La démo compare un dessin de test.</td></tr>
</table>

## Un téléphone surveille. Un autre vous prévient.

Associez les téléphones par code QR. Le texte chiffré passe par le relais de votre choix ; les images restent sur le téléphone qui surveille. [Guide d’association](docs/community/PAIRING.md).

<table>
<tr><th>Envoyer une alerte</th><th>La recevoir sur un autre téléphone</th></tr>
<tr><td align="center" width="50%"><img width="240" src="docs/community/images/paired-send.png" alt="Émetteur après l’envoi d’une alerte de test chiffrée"></td><td align="center" width="50%"><img width="240" src="docs/community/images/paired-receive.png" alt="Autre appareil récepteur affichant la même alerte de test"></td></tr>
</table>

Illustration : une alerte de test entre deux émulateurs via un relais public. La réception dépend du réseau, de la disponibilité du relais et des réglages de batterie d’Android.

<a name="get-started"></a>

## Essayer Be Your Eye

**Version de développement : aucun APK public pour le moment.** Compilez l’application Community et suivez le guide. Releases ne contient actuellement que les fichiers des modèles.

**[Compiler les sources](docs/community/BUILD.md)** · [Guide d’utilisation](USER_MANUAL.md)

**Téléphone de surveillance :** Android 8 ou ultérieur, arm64 et 8 Go de RAM. Fixez-le, branchez-le et gardez l’application visible. Changer d’application ou verrouiller l’écran arrête la surveillance.

La lecture numérique est la fonction principale ; les deux autres modes sont expérimentaux. Les cibles prises en charge se limitent à un catalogue. La précision sur téléphone réel et la fiabilité dans la durée restent à vérifier. Ce n’est pas une alarme de sécurité. [Prérequis et limites](docs/community/DEVICE_SUPPORT.md).

## Aidez-nous à le faire évoluer

Essayez un cas concret, signalez un bug ou améliorez une traduction. [Contribuer](CONTRIBUTING.md) · [Architecture](docs/ARCHITECTURE.md) · [Sécurité](SECURITY.md).

## Licence

**Utilisation, modification et redistribution gratuites, y compris à des fins commerciales.** Le code du projet est sous [Apache-2.0](LICENSE), sans autorisation supplémentaire. Conservez les licences, attributions et mentions de modifications requises. Les [modèles](docs/community/MODELS.md), les [vidéos de démonstration](docs/community/demos/SOURCES.md) et les [dépendances](THIRD_PARTY_NOTICES.md) conservent leurs propres licences.

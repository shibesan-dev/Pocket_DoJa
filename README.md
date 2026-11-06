![Pocket DoJa Banner](docs/images/banner.png)

# Pocket DoJa™

**Pocket DoJa** is an Android-based emulator project based on OpenDoJa, dedicated to exploring and preserving the experience of classic NTT DoCoMo i-mode Java games.

The project aims to make these nostalgic mobile games more accessible on modern Android devices, with features including game library management, customizable animated themes, and background music support.

Compatibility may vary depending on the game, DoJa version, and device. Some games or features may not work as expected.

## Notice

This is a personal hobby project by Shibesan. The only official source of updates is this repository: https://github.com/shibesan-dev

Any social media accounts, websites, or channels claiming to be me or this project outside of the link above are **scams**. Please follow updates here only. Official channels will be listed in this README if they become available in the future.

## Features

- Emulator for classic DoJa / i-mode Java games
- Game library management (`.jam` / `.jar` loading)
- 5 animated themes to choose
- Background music (BGM) support
- Controller / touch input mapping
- Change game icon and rename
- Per-game config (Screen Orientation/Display/Hide Keypad)

## Screenshots

| | |
|---|---|
| ![Screenshot 1](docs/images/screenshot-1.png) | ![Screenshot 2](docs/images/screenshot-2.png) |
| ![Screenshot 3](docs/images/screenshot-3.png) | ![Screenshot 4](docs/images/screenshot-4.png) |
| ![Screenshot 5](docs/images/screenshot-5.png) | |

## Building

Requirements:

- Android SDK (compileSdk 34, minSdk 28)
- JDK 17
- Gradle (wrapper included)

Build (debug):

```bash
./gradlew :app:assembleDebug
```

On Windows:

```bat
gradlew.bat :app:assembleDebug
```
## Logging/Debugging

- Enable logging in settings in the emulator.
- Open the game that has issue.
- Go to settings and disable logging.
- The log file is saved in \Android\data\com.shibesan.pocketdoja\ folder. 

## Project Structure

- `app/` — Android application module
- `opendoja/` — OpenDoJa host/runtime (Java)
- `dexlib/` — DEX utilities
- `public/` — Themes and assets (game files are *not* distributed)
- `configs/` — Runtime configuration
- `docs/images/` — Screenshots

## Disclaimer

Pocket DoJa does not provide or distribute game files. Users are responsible for obtaining their own files and ensuring their use complies with applicable laws and copyright. Pocket DoJa is not affiliated with or endorsed by NTT DOCOMO, game developers, or publishers. All trademarks belong to their respective owners.

## Credits

- OpenDoJa — For the original project that made Pocket DoJa possible.
- Wandering Hunter (流浪的猎人) — For creating the original Android port.
- Shibesan (aka DominicTei) — The main developer of Pocket DoJa.

## Support the Project

If you enjoy Pocket DoJa and would like to support its continued development, you can make a voluntary donation using one of the options below.

**PayPal:** https://www.paypal.com/ncp/payment/CCLQWPK38LFB4

**Cryptocurrency:**<br>
**Network:** ETH, BSC (BEP-20), and POLYGON based networks are supported!<br>
**Currency:** USDT, BNB, ETH, and POLYGON accepted<br>
**Wallet address:** 0xB3Edf36f3654779ACdE275744a8cC31C37BE9d6c

Donations are entirely optional and help support ongoing development and preservation efforts.

Additionally, feel free to donate to official OpenDoJa team if you want to support the community. Please visit "https://github.com/GrenderG/openDoJa"
If you need more information or related resources, please visit "https://keitaiarchive.org/"

Thank you for your interest in Pocket DoJa!

© 2026 Pocket DoJa. Original themes, artwork, and branding are owned by the Pocket DoJa developer, except where otherwise stated.

Pocket DoJa is based on OpenDoJa and distributed under GNU GPLv3. Third-party components remain subject to their respective licenses.

Unofficial forks are not necessarily affiliated with or endorsed by the original Pocket DoJa project.

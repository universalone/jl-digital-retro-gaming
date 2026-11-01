# JL Digital Retro Gaming

The JL Digital game library and RetroArch 1.22.2 built as one Android app, with the
"JL Digital Arcade" menu theme (neon colors, 8-bit Press Start 2P headers).

## Install

Download the latest app (one APK for phones, NVIDIA Shield, onn 4K Pro and Mi Box):

    https://github.com/universalone/jl-digital-retro-gaming/releases/latest/download/JL-Digital-Retro-Gaming.apk

On a TV box, type that link into the Downloader app. Emulator cores download
automatically the first time a system is played. Games are not included: use
"Download my games" in the app with a share link to your own game pack.

## What changed from RetroArch

- `pkg/android/jl/` - the combined app (launcher + RetroArch), Gradle project.
- `menu/drivers/ozone.c`, `configuration.c`, `intl/msg_hash_us.h` - theme, default
  menu and names (applied by `jl/scripts/jl_theme.py`).
- `jl/web/` - the game library page; `jl/scripts/build_assets.ps1` bundles it with
  the menu assets into `pkg/android/jl/assets`.

## Build

JDK 17, Android SDK with NDK 22.0.7026061 (on a path without spaces), Gradle 8.7:

    jl/scripts/build_assets.ps1
    cd pkg/android/jl
    gradle assembleUniversalRelease

Release builds are signed with a key kept outside this repository.

## License

RetroArch is licensed under the GNU GPL v3 (see `COPYING`); this fork is too.
Press Start 2P is under the SIL Open Font License.

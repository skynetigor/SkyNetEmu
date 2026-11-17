# Notices

SkyNetEmu is licensed under the **GNU General Public License v3.0 only** (see [LICENSE](LICENSE)).

## Upstream attribution

SkyNetEmu descends from Winlator and its forks (Winlator Bionic, Winlator Ludashi). Winlator is distributed under the MIT license, which requires this notice to stay with the derived code:

> Copyright (c) 2023 BrunoSX
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

The combined work is distributed under GPL-3.0-only; MIT is GPL-compatible.

## Bundled third-party components

These keep their own licenses, which are all compatible with GPL-3.0. Their license texts are in the source tree next to them.

| Component | Location | License |
| --- | --- | --- |
| PRoot | `app/src/main/cpp/proot` | GPL-2.0-or-later |
| virglrenderer | `app/src/main/cpp/virglrenderer` | MIT |
| OpenXR SDK | `app/src/main/cpp/OpenXR-SDK` | Apache-2.0 (with its own `LICENSES/`) |
| libadrenotools | `app/src/main/cpp/adrenotools` | BSD-style, see the submodule |
| lsfg-vk and Android port, pe-parse, toml11, volk, dxbc | `app/src/main/assets/lsfg_vk` | MIT / zlib, see `THIRD_PARTY_NOTICES.md` |
| FEX Vulkan compat | `app/src/main/assets/linux` | MIT |
| HarfBuzz 2.7.4, FriBidi 1.0.8, libthai 0.1.28, libdatrie 0.2.13, Graphite2 1.3.14, Pixman 0.40.0 (unmodified Debian 11 x86-64 builds, loaded by Counter-Strike 2 under FEX; sources: the Debian 11 source packages) | `app/src/main/assets/linux/sniper-fontlibs` | MIT (HarfBuzz, Pixman), LGPL-2.1-or-later (FriBidi, libthai, libdatrie), LGPL-2.1+ / MPL-1.1+ / GPL-2+ (Graphite2) |
| MidiSynth | `app/libs/MidiSynth` | Apache-2.0 |
| Soundfont `wt_210k_G` | `app/src/main/assets/soundfonts` | CC BY 3.0 (data file, not code) |

Lossless Scaling's `Lossless.dll` is not included; users supply their own copy.

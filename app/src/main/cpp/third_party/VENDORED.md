# Vendored native libraries

| Library | Version | Source | SHA-256 of source archive | License |
|---|---|---|---|---|
| libopus | 1.5.2 | https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz | `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1` | BSD-3-Clause (`opus/COPYING`) |
| RNNoise | v0.1.1 | https://gitlab.xiph.org/xiph/rnnoise/-/archive/v0.1.1/rnnoise-v0.1.1.tar.gz | `d6f0777d85ccbe3ddf18074dfe620f4c0816d0c2d357d5c1f9f8df4d9671702f` | BSD-3-Clause (`rnnoise/COPYING`) |

Trimmed to what the app build needs:

- **opus**: kept `CMakeLists.txt`, `cmake/`, `configure.ac`, `Makefile.am` and `*.mk` (all read by the CMake build), `package_version`, `celt/`, `silk/`, `src/`, `include/` and the
  `dnn/*.h` headers (referenced behind `#ifdef`s). Removed the DNN sources and model data (DRED / Deep PLC / OSCE
  are disabled), tests, docs, meson files and the demo programs.
- **rnnoise**: kept `include/` and `src/` (minus the training-only `rnn_reader.c`). The v0.1.1 model is compiled in
  from `src/rnn_data.c`. Its internal symbols are `rnn_`-prefixed, so it links next to libopus without clashes.

# PS3Dec

A containerised alternative ISO encryptor/decryptor for PS3 disc images with a web GUI.

This is started from the modified version of [PS3Dec r5](https://github.com/al3xtjames/PS3Dec), but uses mbedTLS 4.x (via the PSA Crypto API) for AES encryption/decryption and CMake as the build system.

This was built because there are many GUI tools on Windows, but not many on Linux. Additionnaly, the container format makes this fully portable and avoids the headache of compiling PS3Dec which needs very outdated dependencies.

## Quick start

```sh
mkdir -p iso keys output          # put .iso files in ./iso and their .dkey files in ./keys
docker compose up -d --build
```

Then open <http://localhost:8000>. Converted images appear in `./output`.

Without compose:

```sh
docker build -t ps3dec .
docker run --rm -p 127.0.0.1:8000:8000 --user "$(id -u):$(id -g)" \
  -v "$PWD/iso:/data/iso:ro" -v "$PWD/keys:/data/keys:ro" -v "$PWD/output:/data/output" \
  ps3dec
```

Create the three folders first: if a bind-mounted folder is missing, Docker creates it as root and the container cannot write to it (the compose file refuses to start instead). `--user` (or `PS3DEC_UID` / `PS3DEC_GID` for compose) makes the files in `./output` yours; the image itself runs as UID 1000.

### Web GUI

A small local web UI around the PS3Dec tool: pick a disc image and its key, press Start, watch progress. 
It runs in one container (FastAPI backend serving a React frontend) and works on folders you mount into it. 
Nothing is uploaded or downloaded through the browser.

**Local use only.** There is no authentication and one job runs at a time. Keep the port bound to `127.0.0.1` and do not expose it to a network.

### Command Line Interface

Since the PS3Dec binary is compiled inside the container, it's still possible to run it manually inside the container, e.g:

```sh
docker compose exec ps3dec ps3dec d key <disc_key_hex> /data/iso/disc.iso /data/output/disc.dec.iso
```

Without compose:

```sh
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$PWD/iso:/data/iso:ro" -v "$PWD/keys:/data/keys:ro" -v "$PWD/output:/data/output" \
  --entrypoint ps3dec ps3dec \
  d d1 <d1_hex> /data/iso/disc.iso /data/output/disc.dec.iso
```

### Folders

| Container path | Purpose | Mount |
|---|---|---|
| `/data/iso` | input `.iso` files (top level only, no subfolders) | read-only |
| `/data/keys` | `.dkey` files | read-only |
| `/data/output` | results | read-write |

An existing file in output is only replaced after you confirm, and only once the new one is complete (the tool writes to `<name>.iso.ps3dec.part` and renames it on success; stale `.ps3dec.part` files are removed at startup).

### Keys

- A `.dkey` file holds the disc's **D1** as 32 hex characters (an optional `0x` prefix and surrounding whitespace are fine), as distributed by redump.
- A key is suggested for an ISO when the names match apart from the extension, ignoring case:
  `Game.iso` + `game.dkey`. You can pick another key by hand.
- 3k3y images carry their own key and need none.
- **The tool cannot tell whether a key is wrong.** With the wrong key a job still finishes "successfully" and writes an image whose encrypted regions are garbage. Check the key if the result does not boot or mount.

### Configuration

All optional; the defaults match the container layout above.

| Variable | Default | |
|---|---|---|
| `PORT` | `8000` | port inside the container |
| `PS3DEC_ISO_DIR` | `/data/iso` | |
| `PS3DEC_KEYS_DIR` | `/data/keys` | |
| `PS3DEC_OUTPUT_DIR` | `/data/output` | |
| `PS3DEC_BIN` | `/usr/local/bin/ps3dec` | the PS3Dec binary |
| `PS3DEC_STATIC_DIR` | `/app/static` | built frontend |

Job state is held in memory, so the app must run as a **single worker** (the default `python -m app` does; do not start it with several).

## How the image is built

Four stages, all on Debian trixie: mbedTLS 4.2.0 is built from its release tarball (SHA-256 checked) and linked statically into PS3Dec; the frontend is built with Node and pnpm; the backend's Python dependencies are installed into a venv; the final stage contains only Python, `libgomp`, the venv, the binary and the built frontend, with no compilers or Node.

## Development

```sh
# backend (needs uv); the tests use build/Release/PS3Dec when it exists (see Compilation below)
cd backend && uv sync && uv run pytest
PS3DEC_ISO_DIR=../iso PS3DEC_KEYS_DIR=../keys PS3DEC_OUTPUT_DIR=../output \
  PS3DEC_BIN=../build/Release/PS3Dec PS3DEC_STATIC_DIR=/nonexistent uv run python -m app

# frontend (needs pnpm); the dev server proxies /api to localhost:8000
cd frontend && pnpm install && pnpm dev
pnpm test
```


## Dependencies
#### Windows
 - Visual Studio 2017 (with Visual Studio C++ tools for CMake installed)
 - mbedTLS 4.x (e.g. via [vcpkg](https://vcpkg.io): `vcpkg install mbedtls`);
   pass the vcpkg toolchain file to CMake so it can be found

#### *nix
 - A compiler with OpenMP support
 - CMake
 - [Ninja](https://ninja-build.org/) (optional)
 - mbedTLS 4.x (e.g. `pacman -S mbedtls`)

On macOS, libomp and mbedtls must be installed (available in Homebrew).

### Compilation
#### Windows
1. `git clone https://github.com/tonyp7/PS3Dec`
2. In Visual Studio: Select `File > Open > CMake...` and open
   PS3Dec/CMakeLists.txt
3. Change the current configuration to `x64-Release`
4. Select `Build > Build Current Document (CMakeLists.txt)`
5. Select `CMake > Cache > Open Cache Folder (x64-Release Only) > PS3Dec`
6. Run the PS3Dec binary (`RelWithDebInfo\PS3Dec.exe`)

#### *nix
1. `git clone https://github.com/tonyp7/PS3Dec && cd PS3Dec`
2. `mkdir build && cd build`
3. `cmake -G Ninja .. && ninja` if Ninja is installed; otherwise,
   `cmake .. && make`
4. Run the PS3Dec binary (`Release/PS3Dec`)

## License

In the spirit of the original PS3Dec code, this is released as public domain.

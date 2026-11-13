#!/usr/bin/env bash
# Downloads what building the native bridge needs and this repository does not hold:
#   the NVIDIA DLSS SDK, tag v310.9.16: LICENSE.txt, include/, lib/Windows_x86_64/x64/nvsdk_ngx_s.lib (and _d),
#     lib/Windows_x86_64/rel/nvngx_dlss.dll
#   the Khronos Vulkan headers, tag v1.4.368: include/, LICENSE.md, LICENSES/
# into ../dlss-build-deps next to this repository, where gradle.properties looks for them, or into the folder given.
# Only those files are fetched (a shallow, sparse clone without the rest of the history), about 60 MB, most of it
# nvngx_dlss.dll.
#
#   bash native/get-sdk.sh [folder]        (Git Bash on Windows)
#
# Downloading the DLSS SDK means accepting NVIDIA's licence (LICENSE.txt in the SDK). Nothing it fetches may be
# committed to this repository.
set -euo pipefail

DLSS_TAG=v310.9.16
HEADERS_TAG=v1.4.368

repo="$(cd "$(dirname "$0")/.." && pwd)"
deps="${1:-$repo/../dlss-build-deps}"
mkdir -p "$deps"
deps="$(cd "$deps" && pwd)"

# fetch <folder> <url> <tag> <sparse-checkout pattern>...
fetch() {
	local dir="$deps/$1" url="$2" tag="$3"
	shift 3
	if [ -e "$dir" ]; then
		echo "$dir is already there; delete it to download it again."
		return 0
	fi
	git -c core.longpaths=true clone -q --depth 1 --branch "$tag" --filter=blob:none --no-checkout "$url" "$dir"
	git -C "$dir" sparse-checkout init --no-cone
	printf '%s\n' "$@" > "$dir/.git/info/sparse-checkout"
	git -C "$dir" -c core.longpaths=true checkout -q
	echo "$tag of $url in $dir"
}

fetch DLSS https://github.com/NVIDIA/DLSS.git "$DLSS_TAG" \
	/LICENSE.txt /include/ /lib/Windows_x86_64/x64/nvsdk_ngx_s.lib /lib/Windows_x86_64/x64/nvsdk_ngx_d.lib \
	/lib/Windows_x86_64/rel/nvngx_dlss.dll
fetch Vulkan-Headers https://github.com/KhronosGroup/Vulkan-Headers.git "$HEADERS_TAG" \
	/include/vulkan/ /include/vk_video/ /LICENSE.md /LICENSES/

for f in DLSS/include/nvsdk_ngx_vk.h DLSS/lib/Windows_x86_64/x64/nvsdk_ngx_s.lib DLSS/lib/Windows_x86_64/rel/nvngx_dlss.dll \
	Vulkan-Headers/include/vulkan/vulkan.h; do
	if [ ! -f "$deps/$f" ]; then
		echo "Missing after the download: $deps/$f" >&2
		exit 1
	fi
done

# A Windows path: with backslashes for cmd, or with forward slashes for gradle.properties, where a backslash escapes.
win() {
	if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi
}
slashes() {
	if command -v cygpath > /dev/null; then cygpath -m "$1"; else echo "$1"; fi
}
echo
echo "Next, in cmd or PowerShell, build the bridge (the JDK folder can be left out when JAVA_HOME is a JDK 21):"
echo "  \"$(win "$repo/native/build-windows.cmd")\" \"$(win "$deps/DLSS")\" \"$(win "$deps/Vulkan-Headers")\" \"C:\\path\\to\\jdk-21\""
if [ "$deps" != "$(cd "$repo/.." && pwd)/dlss-build-deps" ]; then
	echo "and set dlssSdkDir=$(slashes "$deps/DLSS") in gradle.properties (or pass -PdlssSdkDir=...) before gradlew build."
fi

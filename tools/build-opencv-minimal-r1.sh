#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  build-opencv-minimal-r1.sh \
    --opencv /path/to/opencv-4.13.0 \
    --kleidicv-archive /path/to/kleidicv-0.7.0.tar.gz \
    --ndk /path/to/android-ndk-r29 \
    --sdk /path/to/android-sdk \
    --cmake /path/to/cmake \
    --ninja /path/to/ninja \
    --full-aar /path/to/opencv-4.13.0.aar \
    --out /path/to/pocketprint-opencv-4.13.0-min-r1.aar

arm64-v8a: core + imgproc + java + KleidiCV 0.7.0
armeabi-v7a: core + imgproc + java (KleidiCV intentionally OFF; KleidiCV is AArch64-only)
USAGE
}

OPENCV= KARCHIVE= NDK= SDK= CMAKE= NINJA= FULL_AAR= OUT=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --opencv) OPENCV=$2; shift 2;;
    --kleidicv-archive) KARCHIVE=$2; shift 2;;
    --ndk) NDK=$2; shift 2;;
    --sdk) SDK=$2; shift 2;;
    --cmake) CMAKE=$2; shift 2;;
    --ninja) NINJA=$2; shift 2;;
    --full-aar) FULL_AAR=$2; shift 2;;
    --out) OUT=$2; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2;;
  esac
done
for v in OPENCV KARCHIVE NDK SDK CMAKE NINJA FULL_AAR OUT; do
  [[ -n ${!v:-} ]] || { echo "Missing $v" >&2; exit 2; }
done
[[ -x "$CMAKE" && -x "$NINJA" ]] || { echo "CMake/Ninja not executable" >&2; exit 2; }
[[ -f "$FULL_AAR" && -f "$KARCHIVE" ]] || { echo "AAR/KleidiCV archive missing" >&2; exit 2; }

EXPECTED_KLEIDICV_MD5=e8f94e427bd78a745afa5c8cd073b416
ACTUAL_KLEIDICV_MD5=$(md5sum "$KARCHIVE" | awk '{print $1}')
[[ "$ACTUAL_KLEIDICV_MD5" == "$EXPECTED_KLEIDICV_MD5" ]] || {
  echo "KleidiCV archive mismatch: $ACTUAL_KLEIDICV_MD5" >&2; exit 3;
}

grep -q '#define CV_VERSION_MAJOR    4' "$OPENCV/modules/core/include/opencv2/core/version.hpp"
grep -q '#define CV_VERSION_MINOR    13' "$OPENCV/modules/core/include/opencv2/core/version.hpp"
grep -q '#define CV_VERSION_REVISION 0' "$OPENCV/modules/core/include/opencv2/core/version.hpp"
grep -q 'Pkg.Revision = 29.0.14206865' "$NDK/source.properties"

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
WORK=$(mktemp -d -t pp-opencv-r1-XXXXXX)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/kleidi"
tar -xzf "$KARCHIVE" -C "$WORK/kleidi"
KLEIDICV_SOURCE_PATH="$WORK/kleidi/kleidicv-0.7.0"
[[ -f "$KLEIDICV_SOURCE_PATH/adapters/opencv/CMakeLists.txt" ]] || { echo "Bad KleidiCV archive layout" >&2; exit 3; }

export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
COMMON=(
  -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
  -DCMAKE_BUILD_TYPE=Release -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared
  -DBUILD_LIST=core,imgproc,java -DBUILD_SHARED_LIBS=OFF -DBUILD_opencv_java=ON
  -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF -DBUILD_ANDROID_EXAMPLES=OFF
  -DWITH_IPP=OFF -DWITH_OPENCL=OFF -DWITH_TBB=OFF
  -DWITH_FFMPEG=OFF -DWITH_GSTREAMER=OFF -DWITH_V4L=OFF -DWITH_GTK=OFF -DWITH_QT=OFF
  -DWITH_CUDA=OFF -DWITH_OPENGL=OFF -DWITH_PROTOBUF=OFF
  -DANDROID_SDK="$SDK"
)

for ABI in arm64-v8a armeabi-v7a; do
  B="$WORK/build-$ABI"
  EXTRA=()
  if [[ "$ABI" == arm64-v8a ]]; then
    EXTRA=(-DWITH_KLEIDICV=ON -DKLEIDICV_SOURCE_PATH="$KLEIDICV_SOURCE_PATH")
  else
    EXTRA=(-DWITH_KLEIDICV=OFF)
  fi
  "$CMAKE" -S "$OPENCV" -B "$B" "${COMMON[@]}" -DANDROID_ABI="$ABI" "${EXTRA[@]}"
  "$CMAKE" --build "$B" --target opencv_java -j "$(nproc)"
  SO=$(find "$B" -type f -name libopencv_java4.so | head -1)
  [[ -f "$SO" ]] || { echo "libopencv_java4.so missing for $ABI" >&2; exit 4; }
  mkdir -p "$WORK/aar/jni/$ABI"
  cp "$SO" "$WORK/aar/jni/$ABI/libopencv_java4.so"
  "$STRIP" --strip-unneeded "$WORK/aar/jni/$ABI/libopencv_java4.so"
  case "$ABI" in arm64-v8a) TRIPLE=aarch64-linux-android;; armeabi-v7a) TRIPLE=arm-linux-androideabi;; esac
  CXX=$(find "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$TRIPLE" -name libc++_shared.so | sort -V | tail -1)
  cp "$CXX" "$WORK/aar/jni/$ABI/libc++_shared.so"
  "$STRIP" --strip-unneeded "$WORK/aar/jni/$ABI/libc++_shared.so"
done

unzip -q "$FULL_AAR" -d "$WORK/full"
cp "$WORK/full/classes.jar" "$WORK/aar/classes.jar"
cp "$WORK/full/AndroidManifest.xml" "$WORK/aar/AndroidManifest.xml"
[[ -f "$WORK/full/R.txt" ]] && cp "$WORK/full/R.txt" "$WORK/aar/R.txt" || true
[[ -d "$WORK/full/res" ]] && cp -a "$WORK/full/res" "$WORK/aar/res" || true
[[ -d "$WORK/full/META-INF" ]] && cp -a "$WORK/full/META-INF" "$WORK/aar/META-INF" || true
mkdir -p "$WORK/aar/META-INF/licenses"
cp "$OPENCV/LICENSE" "$WORK/aar/META-INF/licenses/OpenCV-Apache-2.0.txt"
cp "$KLEIDICV_SOURCE_PATH/LICENSES/Apache-2.0.txt" "$WORK/aar/META-INF/licenses/KleidiCV-Apache-2.0.txt"

mkdir -p "$(dirname "$OUT")"; rm -f "$OUT"
(cd "$WORK/aar" && zip -q -9 -r "$OUT" .)
unzip -tq "$OUT" >/dev/null
sha256sum "$OUT"

#!/usr/bin/env bash
# Runs the benchmark on the connected phone instead of this computer, which only compiles it once.
# It runs through app_process, without ahead-of-time compilation: scores match the desktop run,
# but timings only compare with each other. The run survives the USB cable being pulled.
#
# Usage: tools/pinyin/bench/run_bench_phone.sh <cases.tsv to write here> [BenchHarness options]
#   e.g. tools/pinyin/bench/run_bench_phone.sh /tmp/cases.tsv --taps --latency-every 0
# The language model is always on.
set -euo pipefail
cd "$(dirname "$0")/../../.."
CASES=$1
shift
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
ADB="$SDK/platform-tools/adb"
D8="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/d8"
C="$HOME/.gradle/caches/modules-2/files-2.1"
KV="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
jar() { find "$C/$1" -name "$2" -not -name '*sources*' | sort | tail -1; }
STD="$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-[0-9]*.jar')"
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KV.jar"):$STD"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-script-runtime 'kotlin-script-runtime-[0-9]*.jar')"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-[0-9]*.jar')"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable 'kotlin-daemon-embeddable-[0-9]*.jar')"
CP="$CP:$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-*.jar'):$(jar org.jetbrains/annotations 'annotations-*.jar')"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
P=app/src/main/java/dev/zain/znkeyboard/pinyin
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jdk-home "$JAVA_HOME" \
  -no-stdlib -no-reflect -classpath "$STD" -nowarn -d "$OUT/bench.jar" \
  "$P/PinyinFuzzy.kt" "$P/PinyinDictionary.kt" "$P/PinyinLanguageModel.kt" "$P/PinyinEngine.kt" "$P/PinyinUserData.kt" "$P/PinyinLearner.kt" "$P/SentenceDecoder.kt" "$P/NineKeyEngine.kt" \
  app/src/main/java/dev/zain/znkeyboard/typing/TouchModel.kt app/src/main/java/dev/zain/znkeyboard/typing/CharLanguageModel.kt \
  app/src/main/java/dev/zain/znkeyboard/typing/TouchDecoder.kt tools/pinyin/bench/BenchHarness.kt
"$D8" --release --min-api 26 --output "$OUT" "$OUT/bench.jar" "$STD" 2>/dev/null

DIR=/data/local/tmp/znbench
"$ADB" shell mkdir -p "$DIR"
"$ADB" push -q "$OUT/classes.dex" "$DIR/bench.dex"
# The data only changes with the dictionary; skip the copy when it is already there.
size() { "$ADB" shell "stat -c %s $DIR/$1 2>/dev/null" | tr -d '\r'; }
for file in app/src/main/assets/pinyin/dict.tsv app/src/main/assets/pinyin/lm.bin tools/pinyin/bench/bench.tsv; do
  [ "$(size "$(basename "$file")")" = "$(stat -f %z "$file")" ] || "$ADB" push -q "$file" "$DIR/"
done
"$ADB" shell "rm -f $DIR/done $DIR/cases.tsv; cd $DIR; nohup sh -c 'CLASSPATH=$DIR/bench.dex app_process -Xmx3g $DIR BenchHarnessKt dict.tsv bench.tsv --lm lm.bin --cases cases.tsv $* > out.txt 2>&1; touch done' > /dev/null 2>&1 &"
until "$ADB" shell "[ -f $DIR/done ]" 2>/dev/null; do sleep 15; done
"$ADB" shell cat "$DIR/out.txt"
"$ADB" pull -q "$DIR/cases.tsv" "$CASES"

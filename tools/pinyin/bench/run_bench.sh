#!/usr/bin/env bash
# Compiles the pure-Kotlin pinyin engine with BenchHarness.kt using the Kotlin compiler in the
# Gradle cache (run a Gradle build once first), then scores it on bench.tsv.
#
# Usage: tools/pinyin/bench/run_bench.sh [--lm lm.bin] [--taps] [--strict] [--cases out.tsv] [--latency-every N]
#   --lm lm.bin        rank sentences with this language model (app/src/main/assets/pinyin/lm.bin)
#   --taps             give the decoder each letter's tap, as the keyboard does
#   --strict           fuzzy sounds off (the keyboard starts with all of them on)
#   --cases out.tsv    also write every case with what the engine gave, for diffing two runs
#   --latency-every N  time every keystroke of every Nth case (default 10, 0 to skip)
set -euo pipefail
cd "$(dirname "$0")/../../.."
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
C="$HOME/.gradle/caches/modules-2/files-2.1"
KV="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
jar() { find "$C/$1" -name "$2" -not -name '*sources*' | sort | tail -1; }
# Gradle may cache neighbouring versions of the runtime jars; take the newest present.
STD="$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-[0-9]*.jar')"
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KV.jar"):$STD"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-script-runtime 'kotlin-script-runtime-[0-9]*.jar')"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-[0-9]*.jar')"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable 'kotlin-daemon-embeddable-[0-9]*.jar')"
CP="$CP:$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-*.jar'):$(jar org.jetbrains/annotations 'annotations-*.jar')"
OUT="$(mktemp -d)"
P=app/src/main/java/dev/zain/znkeyboard/pinyin
"$JAVA_HOME/bin/java" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jdk-home "$JAVA_HOME" \
  -no-stdlib -no-reflect -classpath "$STD" -nowarn -d "$OUT" \
  "$P/PinyinFuzzy.kt" "$P/PinyinDictionary.kt" "$P/PinyinLanguageModel.kt" "$P/PinyinEngine.kt" "$P/PinyinUserData.kt" "$P/PinyinLearner.kt" "$P/SentenceDecoder.kt" \
  app/src/main/java/dev/zain/znkeyboard/typing/TouchModel.kt app/src/main/java/dev/zain/znkeyboard/typing/CharLanguageModel.kt \
  app/src/main/java/dev/zain/znkeyboard/typing/TouchDecoder.kt tools/pinyin/bench/BenchHarness.kt
"$JAVA_HOME/bin/java" -cp "$OUT:$STD" BenchHarnessKt app/src/main/assets/pinyin/dict.tsv tools/pinyin/bench/bench.tsv "$@"

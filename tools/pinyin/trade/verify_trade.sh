#!/usr/bin/env bash
# Compiles the pure-Kotlin pinyin engine with TradeHarness.kt using the Kotlin compiler in the
# Gradle cache (run a Gradle build once first), then scores whole trade sentences
# (tools/pinyin/trade/cases.tsv) in full pinyin, with and without assets/pinyin/trade.tsv.
#
# Usage: tools/pinyin/trade/verify_trade.sh
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
  "$P/PinyinFuzzy.kt" "$P/PinyinDictionary.kt" "$P/PinyinLanguageModel.kt" "$P/PinyinEngine.kt" "$P/EnglishWords.kt" "$P/PinyinUserData.kt" "$P/PinyinLearner.kt" "$P/SentenceDecoder.kt" "$P/PinyinTapBuffer.kt" \
  tools/pinyin/trade/TradeHarness.kt
"$JAVA_HOME/bin/java" -cp "$OUT:$STD" TradeHarnessKt app/src/main/assets/pinyin/dict.tsv app/src/main/assets/pinyin/lm.bin tools/pinyin/trade/cases.tsv tools/pinyin/bench/bench.tsv

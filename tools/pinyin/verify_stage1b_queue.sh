#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
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
"$JAVA_HOME/bin/java" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jdk-home "$JAVA_HOME" \
  -no-stdlib -no-reflect -classpath "$STD" -nowarn -d "$OUT" \
  app/src/main/java/dev/zain/znkeyboard/pinyin/PinyinFuzzy.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/PinyinDictionary.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/PinyinLanguageModel.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/PinyinEngine.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/PinyinUserData.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/PinyinLearner.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/SentenceDecoder.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/NineKeyEngine.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/StrokeIndex.kt \
  app/src/main/java/dev/zain/znkeyboard/pinyin/PinyinTapBuffer.kt \
  app/src/main/java/dev/zain/znkeyboard/ime/LatestWinsDecodeQueue.kt tools/pinyin/Stage1BQueueHarness.kt
"$JAVA_HOME/bin/java" -cp "$OUT:$STD" Stage1BQueueHarnessKt

#!/usr/bin/env bash
# Compiles the pure-Kotlin pinyin engine with PinyinHarness.kt using the Kotlin compiler in the
# Gradle cache (run a Gradle build once first), then checks segmentation, ranking and fuzzy rules
# against the bundled dictionary, plus learning, phrase building and predictions.
#
# Usage: tools/pinyin/verify_pinyin.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
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
  "$P/PinyinFuzzy.kt" "$P/PinyinDictionary.kt" "$P/PinyinEngine.kt" "$P/PinyinUserData.kt" "$P/PinyinLearner.kt" \
  tools/pinyin/PinyinHarness.kt
"$JAVA_HOME/bin/java" -cp "$OUT:$STD" PinyinHarnessKt app/src/main/assets/pinyin/dict.tsv

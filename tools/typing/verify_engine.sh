#!/usr/bin/env bash
# Compiles the pure-Kotlin typing engine together with EngineHarness.kt using the Kotlin compiler
# already in the Gradle cache (no extra project dependencies), then runs unit checks plus an
# end-to-end simulated-typist comparison against the old release-point decoder.
#
# Usage: tools/typing/verify_engine.sh /path/to/count_1w.txt
set -euo pipefail
cd "$(dirname "$0")/../.."
WORDS="${1:?usage: verify_engine.sh /path/to/count_1w.txt (https://norvig.com/ngrams/count_1w.txt)}"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
C="$HOME/.gradle/caches/modules-2/files-2.1"
KV="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
jar() { find "$C/$1" -name "$2" -not -name '*sources*' | sort | tail -1; }
STD="$(jar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$KV.jar")"
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KV.jar"):$STD"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$KV.jar")"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-reflect "kotlin-reflect-$KV.jar")"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$KV.jar")"
CP="$CP:$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-*.jar'):$(jar org.jetbrains/annotations 'annotations-*.jar')"
OUT="$(mktemp -d)"
T=app/src/main/java/dev/zain/znkeyboard/typing
"$JAVA_HOME/bin/java" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jdk-home "$JAVA_HOME" \
  -no-stdlib -no-reflect -classpath "$STD" -nowarn -d "$OUT" \
  "$T/TouchModel.kt" "$T/CharLanguageModel.kt" "$T/TouchDecoder.kt" "$T/TypingLearner.kt" tools/typing/EngineHarness.kt
"$JAVA_HOME/bin/java" -cp "$OUT:$STD" EngineHarnessKt app/src/main/assets/typing/en_char_trigram.bin "$WORDS"

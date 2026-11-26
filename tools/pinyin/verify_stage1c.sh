#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="${1:?Pass a project-external evidence directory}"
mkdir -p "$OUT"
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
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
P=app/src/main/java/dev/zain/znkeyboard/pinyin
COMMON=("$P/PinyinFuzzy.kt" "$P/PinyinDictionary.kt" "$P/PinyinLanguageModel.kt" "$P/PinyinUserData.kt" "$P/PinyinLearner.kt" "$P/SentenceDecoder.kt" "$P/PinyinTapBuffer.kt")
compile() {
  "$JAVA_HOME/bin/java" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jdk-home "$JAVA_HOME" \
    -no-stdlib -no-reflect -classpath "$STD" -nowarn -d "$1" "${COMMON[@]}" "$2" "$3"
}
git show ee5877765eb2de83864e438bb4cb895e56362705:"$P/PinyinEngine.kt" > "$TMP/PinyinEngine.kt"
compile "$TMP/old" "$TMP/PinyinEngine.kt" tools/pinyin/Stage1CReferenceHarness.kt
compile "$TMP/new" "$P/PinyinEngine.kt" tools/pinyin/Stage1CHarness.kt
DICT=app/src/main/assets/pinyin/dict.tsv
LM=app/src/main/assets/pinyin/lm.bin
BENCH=tools/pinyin/bench/bench.tsv
"$JAVA_HOME/bin/java" -cp "$TMP/old:$STD" Stage1CReferenceHarnessKt "$DICT" "$LM" "$BENCH" "$OUT/fixed-scores-reference.txt"
"$JAVA_HOME/bin/java" -cp "$TMP/new:$STD" Stage1CHarnessKt "$DICT" "$LM" "$BENCH" "$OUT/fixed-scores-current.txt"
cmp "$OUT/fixed-scores-reference.txt" "$OUT/fixed-scores-current.txt"
echo 'protected Stage 1B fixed-score output matches current snapshot output byte for byte'
python3 - "$P/SentenceDecoder.kt" "$TMP/SentenceDecoder.kt" <<'PY'
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
source=source.replace('object SentenceDecoder {', 'object SentenceDecoder {\n    var stage1cDecodeCalls = 0', 1)
signature='fun decodeAll(input: String, dictionary: PinyinDictionary, taps: Taps?, before: Context?, limit: Int): List<Sentence> {'
assert source.count(signature)==1
source=source.replace(signature, signature+'\n        stage1cDecodeCalls++', 1)
Path(sys.argv[2]).write_text(source)
PY
COUNT_COMMON=("$P/PinyinFuzzy.kt" "$P/PinyinDictionary.kt" "$P/PinyinLanguageModel.kt" "$P/PinyinUserData.kt" "$P/PinyinLearner.kt" "$TMP/SentenceDecoder.kt" "$P/PinyinTapBuffer.kt")
"$JAVA_HOME/bin/java" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jdk-home "$JAVA_HOME" \
  -no-stdlib -no-reflect -classpath "$STD" -nowarn -d "$TMP/count" \
  "${COUNT_COMMON[@]}" "$P/PinyinEngine.kt" tools/pinyin/Stage1CDecodeCountHarness.kt
"$JAVA_HOME/bin/java" -cp "$TMP/count:$STD" Stage1CDecodeCountHarnessKt "$DICT" "$LM"

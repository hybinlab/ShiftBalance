#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mvn -o -q test-compile dependency:build-classpath -Dmdep.outputFile=target/desktop.classpath
classpath=$(cat target/desktop.classpath)
java -ea -cp "target/test-classes:target/classes:$classpath" com.shiftbalance.desktop.DesktopSelfCheck "$@"

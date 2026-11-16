#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mvn -o -q test-compile dependency:build-classpath -Dmdep.outputFile=target/rules-self-check.classpath
classpath=$(cat target/rules-self-check.classpath)
java -ea -cp "target/test-classes:target/classes:$classpath" com.shiftbalance.schedule.ConfigurableRulesSelfCheck "$@"

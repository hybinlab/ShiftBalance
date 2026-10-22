#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/fixed-person-self-check.classpath
classpath=$(cat target/fixed-person-self-check.classpath)
java -ea -cp "target/test-classes:target/classes:$classpath" com.shiftbalance.schedule.FixedPersonRuleSelfCheck

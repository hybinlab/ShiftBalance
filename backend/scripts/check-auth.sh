#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/auth-self-check.classpath
classpath=$(cat target/auth-self-check.classpath)
java -ea -cp "target/test-classes:target/classes:$classpath" com.shiftbalance.auth.AuthSelfCheck

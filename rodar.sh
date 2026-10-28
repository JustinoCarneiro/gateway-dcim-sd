#!/usr/bin/env bash
# Uso: ./rodar.sh <Classe> [argumentos]    ex.: ./rodar.sh Gateway
#      ./rodar.sh SensorTemperatura temp-rack-A A
# Propriedades -D passam por JAVA_OPTS, ex.: JAVA_OPTS="-Ddc.interface=wlo1 -Ddc.descoberta.s=8"
set -euo pipefail
cd "$(dirname "$0")"

[ -d target/classes/dc ] || mvn -q compile
[ -f target/cp.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

classe="$1"; shift
exec java ${JAVA_OPTS:-} -cp "target/classes:$(cat target/cp.txt)" "dc.${classe}" "$@"

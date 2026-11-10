#!/bin/sh
set -e
rm -rf out
mkdir -p out
javac -encoding UTF-8 -d out $(find src -name "*.java")
echo "Build OK. Run with: java -cp out com.classcontrol.ui.app.ConsoleMain --mock"

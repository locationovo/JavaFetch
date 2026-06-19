#!/bin/bash
set -e

echo "==> Cleaning previous build..."
rm -rf out JavaFetch.jar

echo "==> Compiling..."
mkdir -p out
javac -d out src/JavaFetch.java

echo "==> Packaging JAR..."
cp MANIFEST.MF out/
cp javafetch.conf out/
cd out
jar cvfm ../JavaFetch.jar MANIFEST.MF *.class javafetch.conf
cd ..

echo "==> Done: JavaFetch.jar"

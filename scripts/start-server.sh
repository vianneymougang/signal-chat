#!/bin/bash
set -e

echo "Démarrage du serveur SignalChat sur le port 9000..."
echo ""

JAR="server/target/server.jar"

if [ ! -f "$JAR" ]; then
    echo "ERREUR : $JAR introuvable."
    echo "Lancez d’abord : mvn clean package -DskipTests"
    exit 1
fi

java -jar "$JAR"

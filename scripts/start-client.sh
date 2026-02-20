#!/bin/bash
# Usage: ./scripts/start-client.sh [USERNAME] [PORT] [SERVER_IP]
#   USERNAME  : nom de l’utilisateur (défaut: Alice)
#   PORT      : port du client       (défaut: 8081)
#   SERVER_IP : IP du serveur        (défaut: localhost)
#
# Exemples :
#   ./scripts/start-client.sh Alice 8081
#   ./scripts/start-client.sh Bob   8082 192.168.1.10

set -e

USERNAME=${1:-Alice}
PORT=${2:-8081}
SERVER=${3:-http://localhost:9000}

echo "Démarrage du client $USERNAME sur le port $PORT"
echo "Serveur : $SERVER"
echo ""

JAR="client/target/client.jar"

if [ ! -f "$JAR" ]; then
    echo "ERREUR : $JAR introuvable."
    echo "Lancez d’abord : mvn clean package -DskipTests"
    exit 1
fi

java -jar "$JAR" \
    --signalchat.username="$USERNAME" \
    --server.port="$PORT" \
    --signalchat.server-url="$SERVER"

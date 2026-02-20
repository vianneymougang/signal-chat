@echo off
echo Démarrage du serveur SignalChat sur le port 9000...
echo.

set JAR=server\target\server.jar

if not exist "%JAR%" (
    echo ERREUR : %JAR% introuvable.
    echo Lancez d’abord : mvn clean package -DskipTests
    exit /b 1
)

java -jar "%JAR%"

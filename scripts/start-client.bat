@echo off
rem Usage: start-client.bat [USERNAME] [PORT] [SERVER_IP]
rem   USERNAME  : nom de l’utilisateur (défaut: Alice)
rem   PORT      : port du client       (défaut: 8081)
rem   SERVER_IP : IP du serveur        (défaut: localhost)

set USERNAME=%~1
set PORT=%~2
set SERVER=%~3

if "%USERNAME%"=="" set USERNAME=Alice
if "%PORT%"=="" set PORT=8081
if "%SERVER%"=="" set SERVER=localhost

echo Démarrage du client %USERNAME% sur le port %PORT%
echo Serveur : %SERVER%:9000
echo.

set JAR=client\target\client.jar

if not exist "%JAR%" (
    echo ERREUR : %JAR% introuvable.
    echo Lancez d’abord : mvn clean package -DskipTests
    exit /b 1
)

java -jar "%JAR%" ^
    "--signalchat.username=%USERNAME%" ^
    "--server.port=%PORT%" ^
    "--signalchat.server-url=http://%SERVER%:9000"

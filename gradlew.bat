@echo off
set APP_HOME=%~dp0
set JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%JAR%" (
  echo Missing gradle\wrapper\gradle-wrapper.jar
  echo Install Gradle and run: gradle wrapper --gradle-version 8.10.2
  exit /b 1
)
java -jar "%JAR%" %*

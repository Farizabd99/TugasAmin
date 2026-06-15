@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
echo Running Android Unit Tests...
call gradlew.bat testDebugUnitTest --no-daemon 2>&1

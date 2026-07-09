@echo off
echo Building Storage Overlay mod...
echo.

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

if exist ".gradle" (
    echo Clearing build cache...
    rmdir /s /q .gradle
)

call gradlew.bat build

if errorlevel 1 (
    echo.
    echo BUILD FAILED. Check the output above for errors.
    pause
    exit /b 1
)

echo.
echo BUILD SUCCESSFUL!
echo Your mod jar is in: build\libs\
echo Copy the jar (not the -sources.jar) to your .minecraft\mods\ folder.
echo.
pause

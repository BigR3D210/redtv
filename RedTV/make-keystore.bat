@echo off
REM Generates a release signing keystore for Red TV (Windows).
REM Requires keytool (bundled with Android Studio's JDK or any JDK on PATH).
setlocal
set ALIAS=redtv
set STORE=redtv-release.jks

if exist %STORE% (
  echo %STORE% already exists. Delete it first if you really want a new one.
  exit /b 1
)

set /p PASS=Choose a keystore password:

keytool -genkeypair -v -keystore %STORE% -alias %ALIAS% -keyalg RSA -keysize 2048 -validity 10000 -storepass %PASS% -keypass %PASS% -dname "CN=Red TV, OU=Personal, O=Red, L=City, S=State, C=US"

(
  echo storeFile=%STORE%
  echo storePassword=%PASS%
  echo keyAlias=%ALIAS%
  echo keyPassword=%PASS%
) > keystore.properties

echo.
echo Done. Created %STORE% and keystore.properties.
echo Now build a signed APK:  gradlew assembleRelease
echo Output: app\build\outputs\apk\release\app-release.apk
echo IMPORTANT: back up %STORE% - you need the SAME keystore to update the app later.
endlocal

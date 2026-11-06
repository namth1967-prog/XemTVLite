@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title Build Xem TV Lite

echo ==================================================
echo   BUILD XEM TV LITE - tu dong
echo   (lan dau mat 10-20 phut vi phai tai thu vien)
echo ==================================================
echo.

if not exist "settings.gradle.kts" (
  echo [LOI] Khong thay settings.gradle.kts. Hay GIAI NEN file zip truoc, roi chay build.bat trong thu muc XemTVLite.
  goto :fail
)

rem ---------- 1. Tim JDK (uu tien JDK di kem Android Studio) ----------
set "JDK="
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JDK=%ProgramFiles%\Android\Android Studio\jbr"
if not defined JDK if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JDK=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined JDK if exist "%ProgramFiles%\Android\Android Studio Preview\jbr\bin\java.exe" set "JDK=%ProgramFiles%\Android\Android Studio Preview\jbr"
if not defined JDK if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JDK=%JAVA_HOME%"
if not defined JDK (
  echo [LOI] Khong tim thay JDK. Hay cai Android Studio ^(co kem JDK^) tai developer.android.com/studio
  goto :fail
)
set "JAVA_HOME=%JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [OK] JDK: %JAVA_HOME%

rem ---------- 2. Tim Android SDK ----------
set "SDK="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platforms" set "SDK=%ANDROID_HOME%"
if not defined SDK if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platforms" set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK (
  echo [LOI] Khong tim thay Android SDK. Mo Android Studio mot lan de no cai SDK, roi chay lai.
  goto :fail
)
echo [OK] Android SDK: %SDK%
set "SDK_FWD=%SDK:\=/%"
> local.properties echo sdk.dir=%SDK_FWD%

rem ---------- 3. Dong y giay phep Android SDK (neu chua) ----------
if exist "%SDK%\licenses\android-sdk-license" goto :license_ok
echo.
echo Chua dong y giay phep Android SDK. 
choice /c YN /m "Ban dong y giay phep Android SDK de tiep tuc"
if errorlevel 2 goto :fail
set "SDKMGR=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
if not exist "%SDKMGR%" (
  echo [LOI] Chua co cmdline-tools. Mo Android Studio - More Actions - SDK Manager - tab SDK Tools - tick "Android SDK Command-line Tools" - Apply. Sau do chay lai.
  goto :fail
)
powershell -NoProfile -Command "1..40 | ForEach-Object { 'y' } | & '%SDKMGR%' --licenses --sdk_root='%SDK%'" 
:license_ok

rem ---------- 4. Gradle ----------
set "GRADLE="
if exist "gradlew.bat" if exist "gradle\wrapper\gradle-wrapper.jar" set "GRADLE=%~dp0gradlew.bat"
if not defined GRADLE if exist ".tools\gradle-8.9\bin\gradle.bat" set "GRADLE=%~dp0.tools\gradle-8.9\bin\gradle.bat"
if defined GRADLE goto :gradle_ok
echo [..] Dang tai Gradle 8.9 ^(chi lan dau, khoang 130 MB^)...
if not exist ".tools" mkdir ".tools"
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile '.tools\gradle.zip'; Expand-Archive -Force '.tools\gradle.zip' '.tools'; Remove-Item '.tools\gradle.zip'"
if not exist ".tools\gradle-8.9\bin\gradle.bat" (
  echo [LOI] Tai Gradle that bai. Kiem tra mang roi chay lai.
  goto :fail
)
set "GRADLE=%~dp0.tools\gradle-8.9\bin\gradle.bat"
:gradle_ok
echo [OK] Gradle: %GRADLE%

rem ---------- 5. Build ----------
echo.
echo [..] Dang build APK. Dung tat cua so nay...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { & '%GRADLE%' assembleRelease --console=plain 2>&1 | Tee-Object -FilePath 'build.log'; exit $LASTEXITCODE }"
if errorlevel 1 goto :fail_build

set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" (
  echo [LOI] Build xong nhung khong thay file APK.
  goto :fail_build
)
copy /y "%APK%" "XemTVLite.apk" >nul
echo.
echo ==================================================
echo   BUILD THANH CONG
echo   File APK: %~dp0XemTVLite.apk
echo ==================================================
explorer /select,"%~dp0XemTVLite.apk"
pause
exit /b 0

:fail_build
echo.
echo ==================================================
echo   BUILD THAT BAI. Loi day du nam trong file build.log
echo   cung thu muc nay. Hay gui build.log cho Claude.
echo ==================================================
pause
exit /b 1

:fail
echo.
pause
exit /b 1
